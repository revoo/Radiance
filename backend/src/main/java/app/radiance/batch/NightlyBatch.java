package app.radiance.batch;

import app.radiance.content.ContentLibrary;
import app.radiance.jobs.JobKinds;
import app.radiance.jobs.JobRunner;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.Json;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Ulid;
import app.radiance.kernel.WallClock;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.Priority;
import app.radiance.narrative.Maintenance;
import app.radiance.narrative.Renderer;
import app.radiance.news.FixtureHeadlines;
import app.radiance.news.Headline;
import app.radiance.news.HeadlineRepository;
import app.radiance.news.HeadlineSelection;
import app.radiance.persistence.LeaseHolder;
import app.radiance.persistence.Sql;
import app.radiance.persistence.SqlAdmin;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.EventPayload;
import app.radiance.sim.Fidelities;
import app.radiance.sim.ScriptWriter;
import app.radiance.sim.WorldEngine;
import app.radiance.weather.FakeWeather;
import app.radiance.weather.WeatherBook;
import app.radiance.weather.WeatherRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;

/**
 * The nightly batch (SPEC §5.1): air what's due, backfill any gap, write the next script at the
 * fidelity R's idle time calls for, render it, pre-render recap lines, do maintenance, and report. Every
 * step is idempotent: a second run the same night changes nothing, a run killed mid-render picks up its
 * jobs, and renders parked on a budget window finish on a later run.
 *
 * <p>Headlines and weather come from the offline sources (fixtures, {@link FakeWeather}) until M10
 * wires RSS and Open-Meteo.
 */
public final class NightlyBatch {

  public enum Kind { NIGHTLY, BACKFILL, MANUAL }

  /** What one run did; {@code status} is DONE, PARTIAL (renders parked or retrying), DEFERRED or FAILED. */
  public record Report(String id, String status, long scriptFrom, long scriptTo, String fidelity, int planned,
      int rendered, int fellBack, int parked, int dropped, double costUsd, long rowsWritten, String planHash,
      List<String> notes) {}

  private static final Logger log = LoggerFactory.getLogger(NightlyBatch.class);
  static final long HOUR = 3_600_000L;
  static final long DAY = 24 * HOUR;

  private final Sql sql;
  private final SqlAdmin admin;
  private final WriterLane lane;
  private final LeaseHolder lease;
  private final WallClock clock;
  private final ContentLibrary lib;
  private final WorldEngine engine;
  private final ScriptWriter writer;
  private final LlmGateway gateway;
  private final String networkName;
  private final List<CronExpression> crons;
  private final boolean softeningCheck;
  private final AtomicInteger running = new AtomicInteger();

  public NightlyBatch(Sql sql, SqlAdmin admin, WriterLane lane, LeaseHolder lease, WallClock clock, ContentLibrary lib,
      WorldEngine engine, ScriptWriter writer, LlmGateway gateway, String networkName, List<String> crons,
      boolean softeningCheck) {
    this.sql = sql;
    this.admin = admin;
    this.lane = lane;
    this.lease = lease;
    this.clock = clock;
    this.lib = lib;
    this.engine = engine;
    this.writer = writer;
    this.gateway = gateway;
    this.networkName = networkName;
    this.crons = crons.stream().map(NightlyBatch::cron).toList();
    this.softeningCheck = softeningCheck;
  }

  /** Spring's cron has a seconds field; the config uses Unix five-field crons (SPEC §9). */
  static CronExpression cron(String unix) {
    var fields = unix.strip().split("\\s+");
    return CronExpression.parse(fields.length == 5 ? "0 " + unix.strip() : unix.strip());
  }

  public List<CronExpression> crons() {
    return crons;
  }

  /** The next scheduled run after {@code wallMs}, wall time. */
  public long nextRun(long wallMs) {
    long best = Long.MAX_VALUE;
    var at = Instant.ofEpochMilli(wallMs).atZone(ZoneOffset.UTC);
    for (var c : crons) {
      var n = c.next(at);
      if (n != null) best = Math.min(best, n.toInstant().toEpochMilli());
    }
    return best == Long.MAX_VALUE ? wallMs + DAY : best;
  }

  /** The most recent scheduled run at or before {@code wallMs}, wall time. */
  public long lastRun(long wallMs) {
    long best = Long.MIN_VALUE;
    var from = Instant.ofEpochMilli(wallMs - 2 * DAY).atZone(ZoneOffset.UTC);
    for (var c : crons) {
      var t = c.next(from);
      while (t != null && t.toInstant().toEpochMilli() <= wallMs) {
        best = Math.max(best, t.toInstant().toEpochMilli());
        t = c.next(t);
      }
    }
    return best;
  }

  public boolean isRunning() {
    return running.get() > 0;
  }

  public Report run(Kind kind) {
    long started = clock.nowMs();
    String id = Ulid.next(started, RandomSource.seeded(System.nanoTime()).derive("batch"));
    if (!lease.isHeld()) {
      // another process (the running app) holds the lease; it sees the overdue batch and runs it
      admin.execute(new UnitOfWork().add("INSERT INTO batch_run (id, kind, started_at, finished_at, status, error)"
          + " VALUES (?, ?, ?, ?, 'DEFERRED', 'the writer lease is held by another process')", id, kind.name(), started, started));
      log.info("Nightly batch deferred: another process holds the writer lease");
      return new Report(id, "DEFERRED", 0, 0, null, 0, 0, 0, 0, 0, 0, 0, null, List.of("lease held elsewhere"));
    }
    if (running.getAndIncrement() > 0) {
      running.decrementAndGet();
      return new Report(id, "DEFERRED", 0, 0, null, 0, 0, 0, 0, 0, 0, 0, null, List.of("a batch is already running"));
    }
    try {
      lane.run(() -> sql.commit(new UnitOfWork().add("INSERT INTO batch_run (id, kind, started_at, status)"
          + " VALUES (?, ?, ?, 'RUNNING')", id, kind.name(), started)));
      try {
        var report = execute(id, kind, started);
        finish(id, report, null);
        return report;
      } catch (RuntimeException e) {
        log.error("Nightly batch failed", e);
        var failed = new Report(id, "FAILED", 0, 0, null, 0, 0, 0, 0, 0, 0, 0, null, List.of(String.valueOf(e.getMessage())));
        finish(id, failed, e.getClass().getSimpleName() + ": " + e.getMessage());
        return failed;
      }
    } finally {
      running.decrementAndGet();
    }
  }

  private Report execute(String id, Kind kind, long wallStart) {
    var notes = new ArrayList<String>();
    engine.reload(); // the lease may have changed hands since the world was loaded
    var row = sql.queryOne("SELECT time_offset_ms, paused, scripted_until FROM world", List.of(),
        r -> new long[] {r.lng("time_offset_ms"), r.lng("paused"), r.lng("scripted_until")}).orElseThrow();
    if (row[1] != 0) return new Report(id, "DONE", 0, 0, null, 0, 0, 0, 0, 0, 0, 0, null, List.of("the world is paused"));
    long offset = row[0];
    long now = wallStart + offset;
    var w = engine.world();
    if (w.residents().isEmpty()) return new Report(id, "DONE", 0, 0, null, 0, 0, 0, 0, 0, 0, 0, null, List.of("no residents yet"));

    // a run killed mid-render left jobs RUNNING; we hold the lease, so nobody else is running them
    lane.run(() -> sql.commit(new UnitOfWork().add("UPDATE job SET status = 'PENDING', locked_at = NULL WHERE status = 'RUNNING'")));

    // 2. headlines and weather (fixtures and FakeWeather until M10)
    long scripted = row[2];
    long historyFrom = Math.min(scripted, now) - DAY;
    var headlines = headlines(w.seed, w.zone, w.date(historyFrom), w.date(now));
    var hours = new FakeWeather(w.seed, w.zone).hours(historyFrom - historyFrom % HOUR - 8 * DAY, now - now % HOUR + 3 * DAY);
    var uow = new UnitOfWork();
    new WeatherRepository(sql).save(uow, hours);
    new HeadlineRepository(sql).save(uow, headlines);
    lane.run(() -> sql.commit(uow));
    var weather = new WeatherBook(w.zone, lib.weather()).putAll(hours);

    // 3. air what's due; a script that ran out before now is backfilled first
    int planned = 0;
    long rows = 0;
    if (scripted < now) {
      var gap = Fidelities.backfill(scripted, now, engine.knobs());
      if (!gap.isEmpty() && gap.getFirst().fidelity() == null) timeSkip(scripted, gap.getFirst().end());
      var usable = headlines.stream().filter(h -> h.publishedAt() <= now).toList();
      var back = writer.write(gap, usable, weather, primeHours(now, w.zone), ContentOrigin.BACKFILL);
      planned += back.posts() + back.comments() + back.reactions() + back.events() + back.dms();
      rows += back.rowsWritten();
      notes.add("backfilled " + Math.round((now - scripted) / (double) HOUR) + "h in " + gap.size() + " slices");
      if (!back.findings().isEmpty()) notes.add("continuity findings: " + back.findings());
    }
    var aired = engine.advanceTo(now);
    rows += aired.rowsWritten();

    // 4–5. the next script, at the fidelity R's idle time calls for
    var fidelity = Fidelities.forIdle(w.lastSessionAt, now, engine.knobs());
    long from = Math.max(now, scriptedUntil());
    long to = nextRun(wallStart) + offset + (long) (engine.knobs().scriptSlackHours() * HOUR);
    String hash;
    var usable = headlines.stream().filter(h -> h.publishedAt() <= now).toList();
    if (from < to) {
      var slices = fidelity == null ? sliced(from, to) : null;
      var result = slices != null ? writer.write(slices, usable, weather, primeHours(now, w.zone), ContentOrigin.SCRIPT)
          : writer.write(from, to, fidelity, usable, weather, primeHours(now, w.zone), ContentOrigin.SCRIPT);
      planned += result.posts() + result.comments() + result.reactions() + result.events() + result.dms();
      rows += result.rowsWritten();
      if (!result.findings().isEmpty()) notes.add("continuity findings: " + result.findings());
    } else {
      notes.add("the script already reaches " + Instant.ofEpochMilli(from));
    }
    hash = writer.storedHash(now, to);

    // 6. render, earliest first; renders that hit a budget window park and finish on a later run
    double costBefore = cost(wallStart);
    var renderer = new Renderer(sql, lane, lib, gateway, w, networkName, softeningCheck, Priority.SCRIPT);
    var maintenance = new Maintenance(sql, lane, lib, gateway, w, networkName);
    var results = java.util.Collections.synchronizedList(new ArrayList<Renderer.Result>());
    var runner = new JobRunner(sql, lane, clock, 4)
        .on(JobKinds.RENDER_THREAD, j -> results.add(renderer.renderThread(j.refId())))
        .on(JobKinds.RENDER_DM, j -> results.add(renderer.renderDms(j.refId())));
    var renders = runner.drain();
    recapLines(w);

    // 7. maintenance: compaction and summaries (paused under budget pressure)
    maintenance.enqueue(clock.nowMs());
    new JobRunner(sql, lane, clock, 2)
        .on(JobKinds.REFLECT, j -> maintenance.reflect(j.refId()))
        .on(JobKinds.SUMMARY, j -> maintenance.summarize(j.refId()))
        .drain();

    int rendered = results.stream().mapToInt(Renderer.Result::rendered).sum();
    int fellBack = results.stream().mapToInt(Renderer.Result::fellBack).sum();
    long waiting = sql.queryOne("SELECT COUNT(*) AS n FROM job WHERE status IN ('WAITING', 'PENDING')"
        + " AND kind IN ('RENDER_THREAD', 'RENDER_DM')", List.of(), r -> r.lng("n")).orElse(0L);
    String status = waiting > 0 ? "PARTIAL" : "DONE";
    if (waiting > 0) notes.add(waiting + " render jobs waiting (budget window or retry)");
    if (renders.failed() > 0) notes.add(renders.failed() + " render jobs failed");
    log.info("Nightly batch {}: planned {}, rendered {}, parked {}, {}", status, planned, rendered, renders.parked(), notes);
    return new Report(id, status, from, to, fidelity == null ? "NONE" : fidelity.name(), planned, rendered, fellBack,
        (int) waiting, aired.dropped(), cost(wallStart) - costBefore + 0.0, rows, hash, notes);
  }

  private List<app.radiance.sim.Slice> sliced(long from, long to) {
    return Fidelities.structure(from, to);
  }

  private long scriptedUntil() {
    return sql.queryOne("SELECT scripted_until FROM world", List.of(), r -> r.lng("scripted_until")).orElseThrow();
  }

  /** Headlines published from {@code from} to {@code to} (dates), selected per day by region mix. */
  private List<Headline> headlines(long seed, java.time.ZoneId zone, java.time.LocalDate from, java.time.LocalDate to) {
    var fixtures = new FixtureHeadlines(seed, zone, 100);
    var out = new ArrayList<Headline>();
    for (var d = from; !d.isAfter(to); d = d.plusDays(1)) {
      out.addAll(HeadlineSelection.select(fixtures.on(d), engine.knobs().headlinesPerDay(), engine.knobs().regionMix()));
    }
    return out;
  }

  /** R's usual online hours over the last 14 days (SPEC §5.1 prime time), in the world's zone. */
  private Set<Integer> primeHours(long now, java.time.ZoneId zone) {
    var out = new TreeSet<Integer>();
    sql.query("SELECT started_at FROM session_log WHERE started_at >= ?", List.of(now - 14 * DAY), r -> r.lng("started_at"))
        .forEach(t -> out.add(Instant.ofEpochMilli(t).atZone(zone).getHour()));
    return out;
  }

  /** Beyond max-gap, one TIME_SKIP event stands for the stretch nobody saw (SPEC §5.1). */
  private void timeSkip(long from, long to) {
    var payload = new EventPayload("LIFE", Map.of(), Map.of(), null, null, List.of(), null,
        "time passed: " + Math.round((to - from) / (double) DAY) + " days nobody posted about");
    String id = Ulid.next(from, RandomSource.seeded(from).derive("time-skip"));
    lane.run(() -> sql.commit(new UnitOfWork().add("INSERT INTO event (id, world_time, type, importance, visibility, source,"
        + " air_status, payload_json) VALUES (?, ?, 'TIME_SKIP', 1, 'PUBLIC', 'ROUTINE', 'SCRIPTED', ?) ON CONFLICT DO NOTHING",
        id, from, payload.json())));
  }

  /**
   * Every event of importance ≥ 6 gets a recap line (SPEC §5.14): the renderer writes one for events
   * with a post; the rest get one from the event's own words.
   */
  void recapLines(app.radiance.sim.model.SimWorld w) {
    var missing = sql.query("SELECT id, actor_id, payload_json FROM event WHERE importance >= 6 AND recap_line IS NULL"
        + " AND air_status <> 'DROPPED'", List.of(), r -> new String[] {r.str("id"), r.str("actor_id"), r.str("payload_json")});
    if (missing.isEmpty()) return;
    var uow = new UnitOfWork();
    for (var m : missing) {
      var p = EventPayload.parse(m[2]);
      var actor = m[1] == null ? null : w.people.get(m[1]);
      String who = actor == null ? "Someone" : actor.name.split(" ")[0];
      String what = p.arc() != null && p.arc().summary() != null ? p.arc().summary()
          : p.text() == null ? "had news" : p.text();
      uow.add("UPDATE event SET recap_line = ? WHERE id = ?", who + " — " + what, m[0]);
    }
    lane.run(() -> sql.commit(uow));
  }

  private double cost(long since) {
    return sql.queryOne("SELECT COALESCE(SUM(cost_usd), 0) AS c FROM llm_call WHERE created_at >= ?", List.of(since),
        r -> r.dbl("c")).orElse(0.0);
  }

  private void finish(String id, Report r, String error) {
    var report = new LinkedHashMap<String, Object>();
    report.put("planHash", r.planHash());
    report.put("notes", r.notes());
    report.put("fellBack", r.fellBack());
    report.put("parked", r.parked());
    lane.run(() -> sql.commit(new UnitOfWork().add("UPDATE batch_run SET finished_at = ?, status = ?, script_from = ?,"
        + " script_to = ?, fidelity = ?, items_planned = ?, items_rendered = ?, items_dropped = ?, images = 0,"
        + " cost_usd = ?, rows_written = ?, report_json = ?, error = ? WHERE id = ?", clock.nowMs(), r.status(),
        r.scriptFrom(), r.scriptTo(), r.fidelity(), r.planned(), r.rendered(), r.dropped(), r.costUsd(), r.rowsWritten(),
        Json.write(report), error, id)));
  }
}
