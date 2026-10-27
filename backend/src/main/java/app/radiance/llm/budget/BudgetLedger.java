package app.radiance.llm.budget;

import app.radiance.kernel.WallClock;
import app.radiance.llm.Budget;
import app.radiance.llm.BudgetExhaustedException;
import app.radiance.llm.LlmProperties;
import app.radiance.llm.Priority;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongUnaryOperator;

/**
 * Spending and quota windows that reset (SPEC §5.15), on wall time (ADR 0010):
 *
 * <ul>
 *   <li>{@code usd:daily} resets at midnight Central; {@code usd:monthly} on the 1st.
 *   <li>{@code usd:batch} exists only while a batch runs ({@link #openBatch}); work parked on it waits
 *       for the next batch.
 *   <li>{@code <provider>:requests:<per>} and {@code <provider>:tokens:<per>} from the provider's plan.
 *   <li>{@code <provider>:closed} after a 429 or quota error, until the reset the provider reported.
 * </ul>
 *
 * Live calls see only the monthly cap and provider windows; everything else sees all of them. State
 * is persisted to {@code budget_window} so restarts don't forget what was spent.
 */
public final class BudgetLedger implements Budget {

  public static final String DAILY = "usd:daily";
  public static final String MONTHLY = "usd:monthly";
  public static final String BATCH = "usd:batch";
  static final long BATCH_RETRY_MS = 24 * 3_600_000L;

  private static final class Window {
    final String id;
    double used;
    double limit;
    long resetsAt;
    Long closedUntil;
    final LongUnaryOperator nextReset;

    Window(String id, double limit, long now, LongUnaryOperator nextReset) {
      this.id = id;
      this.limit = limit;
      this.nextReset = nextReset;
      this.resetsAt = nextReset.applyAsLong(now);
    }
  }

  private final WallClock clock;
  private final ZoneId zone;
  private final BudgetStore store;
  private final Map<String, Window> windows = new LinkedHashMap<>();
  private final Map<String, List<Window>> providerQuotas = new LinkedHashMap<>();
  private final Set<String> dirty = new LinkedHashSet<>();

  public BudgetLedger(
      LlmProperties.Budgets budgets,
      Map<String, LlmProperties.Provider> providers,
      ZoneId zone,
      WallClock clock,
      BudgetStore store) {
    this.clock = clock;
    this.zone = zone;
    this.store = store;
    long now = clock.nowMs();
    add(new Window(DAILY, budgets.dailyUsd(), now, this::nextMidnight));
    add(new Window(MONTHLY, budgets.monthlyUsd(), now, this::nextMonth));
    providers.forEach((name, p) -> {
      var list = new ArrayList<Window>();
      for (var q : p.quotas()) {
        var reset = resetFor(q);
        String per = q.per().name().toLowerCase();
        if (q.requests() != null) list.add(add(new Window(name + ":requests:" + per, q.requests(), now, reset)));
        if (q.tokens() != null) list.add(add(new Window(name + ":tokens:" + per, q.tokens(), now, reset)));
      }
      providerQuotas.put(name, list);
    });
    restore(store.load());
  }

  private Window add(Window w) {
    windows.put(w.id, w);
    return w;
  }

  private void restore(List<BudgetStore.Row> rows) {
    for (var row : rows) {
      var w = windows.get(row.id());
      if (w == null) {
        if (row.id().endsWith(":closed") && row.closedUntil() != null) {
          closedWindow(row.id().substring(0, row.id().length() - ":closed".length())).closedUntil =
              row.closedUntil();
        }
        continue;
      }
      w.used = row.used();
      w.resetsAt = row.resetsAt();
      w.closedUntil = row.closedUntil();
    }
  }

  /** Live-tuned limits from the Director's console (M13). */
  public synchronized void setLimits(LlmProperties.Budgets budgets) {
    windows.get(DAILY).limit = budgets.dailyUsd();
    windows.get(MONTHLY).limit = budgets.monthlyUsd();
    dirty.add(DAILY);
    dirty.add(MONTHLY);
    var batch = windows.get(BATCH);
    if (batch != null) {
      batch.limit = budgets.perBatchUsd();
      dirty.add(BATCH);
    }
    persist();
  }

  /** Starts a batch run's own spending window. */
  public synchronized void openBatch(double limitUsd) {
    windows.put(BATCH, new Window(BATCH, limitUsd, clock.nowMs(), now -> Long.MAX_VALUE));
    dirty.add(BATCH);
    persist();
  }

  public synchronized void closeBatch() {
    windows.remove(BATCH);
    store.delete(BATCH);
  }

  @Override
  public synchronized void admit(Priority priority, String provider, double estimateUsd, long estimateTokens) {
    long now = clock.nowMs();
    rollover(now);
    var closed = windows.get(provider + ":closed");
    if (closed != null && closed.closedUntil != null && closed.closedUntil > now) {
      throw new BudgetExhaustedException(closed.id, closed.closedUntil);
    }
    for (var w : providerQuotas.getOrDefault(provider, List.of())) {
      double need = w.id.contains(":requests:") ? 1 : estimateTokens;
      if (w.used + need > w.limit) throw new BudgetExhaustedException(w.id, w.resetsAt);
    }
    for (var w : usdWindows(priority)) {
      if (w.used + estimateUsd > w.limit) {
        long resume = w.id.equals(BATCH) ? now + BATCH_RETRY_MS : w.resetsAt;
        throw new BudgetExhaustedException(w.id, resume);
      }
    }
  }

  @Override
  public synchronized void record(Priority priority, String provider, double costUsd, long tokens) {
    long now = clock.nowMs();
    rollover(now);
    if (costUsd > 0) {
      windows.get(DAILY).used += costUsd;
      windows.get(MONTHLY).used += costUsd;
      dirty.add(DAILY);
      dirty.add(MONTHLY);
      var batch = windows.get(BATCH);
      if (batch != null && priority != Priority.LIVE) {
        batch.used += costUsd;
        dirty.add(BATCH);
      }
    }
    for (var w : providerQuotas.getOrDefault(provider, List.of())) {
      w.used += w.id.contains(":requests:") ? 1 : tokens;
      dirty.add(w.id);
    }
    persist();
  }

  @Override
  public synchronized void closeProvider(String provider, long untilMs) {
    var w = closedWindow(provider);
    w.closedUntil = Math.max(untilMs, w.closedUntil == null ? 0 : w.closedUntil);
    w.resetsAt = w.closedUntil;
    dirty.add(w.id);
    persist();
  }

  private Window closedWindow(String provider) {
    return windows.computeIfAbsent(provider + ":closed",
        id -> new Window(id, 0, clock.nowMs(), now -> now));
  }

  @Override
  public synchronized double pressure() {
    rollover(clock.nowMs());
    double max = 0;
    for (var w : usdWindows(Priority.SCRIPT)) {
      if (w.limit > 0) max = Math.max(max, w.used / w.limit);
      else if (w.used > 0) max = Math.max(max, 1);
    }
    return max;
  }

  /** A snapshot for the Director's console cost panel. */
  public synchronized List<BudgetStore.Row> snapshot() {
    rollover(clock.nowMs());
    return windows.values().stream()
        .map(w -> new BudgetStore.Row(w.id, w.used, w.limit, w.resetsAt, w.closedUntil))
        .toList();
  }

  private List<Window> usdWindows(Priority priority) {
    if (priority == Priority.LIVE) return List.of(windows.get(MONTHLY));
    var list = new ArrayList<Window>(List.of(windows.get(DAILY), windows.get(MONTHLY)));
    var batch = windows.get(BATCH);
    if (batch != null) list.add(batch);
    return list;
  }

  private void rollover(long now) {
    for (var w : windows.values()) {
      if (w.closedUntil != null && w.closedUntil <= now) {
        w.closedUntil = null;
        dirty.add(w.id);
      }
      if (w.resetsAt <= now && !w.id.endsWith(":closed")) {
        w.used = 0;
        w.resetsAt = w.nextReset.applyAsLong(now);
        dirty.add(w.id);
      }
    }
  }

  /** Saves only the windows that changed (SPEC §4.4: write only what changed). */
  private void persist() {
    if (dirty.isEmpty()) return;
    var rows = new ArrayList<BudgetStore.Row>();
    for (String id : dirty) {
      var w = windows.get(id);
      if (w != null) rows.add(new BudgetStore.Row(w.id, w.used, w.limit, w.resetsAt, w.closedUntil));
    }
    dirty.clear();
    store.save(rows);
  }

  long nextMidnight(long now) {
    var today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
    return today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
  }

  long nextMonth(long now) {
    var date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
    return date.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli();
  }

  private static LongUnaryOperator resetFor(LlmProperties.Quota q) {
    if (q.per() == LlmProperties.Quota.Per.MINUTE) return now -> (now / 60_000 + 1) * 60_000;
    var tz = q.zone() == null ? ZoneId.of("UTC") : ZoneId.of(q.zone());
    var at = q.resetsAt() == null ? LocalTime.MIDNIGHT : LocalTime.parse(q.resetsAt());
    return now -> {
      ZonedDateTime t = Instant.ofEpochMilli(now).atZone(tz);
      ZonedDateTime candidate = t.toLocalDate().atTime(at).atZone(tz);
      if (!candidate.toInstant().isAfter(t.toInstant())) candidate = candidate.plusDays(1);
      return candidate.toInstant().toEpochMilli();
    };
  }
}
