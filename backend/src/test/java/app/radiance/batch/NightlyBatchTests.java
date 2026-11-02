package app.radiance.batch;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.llm.BudgetExhaustedException;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.LlmRequest;
import app.radiance.llm.LlmResult;
import app.radiance.llm.OfflineGateway;
import app.radiance.llm.PromptBudget;
import app.radiance.llm.Purpose;
import app.radiance.narrative.FakeRender;
import app.radiance.narrative.ProjectedMeter;
import app.radiance.persistence.LeaseHolder;
import app.radiance.persistence.TestDatabase;
import app.radiance.sim.ContinuityAuditor;
import app.radiance.sim.Knobs;
import app.radiance.sim.ScriptWriter;
import app.radiance.sim.WorldEngine;
import app.radiance.world.genesis.SeedImporter;
import app.radiance.world.genesis.SkeletonGenerator;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The nightly batch (SPEC §12 M6 AC), on the seed-7 world with offline models. */
class NightlyBatchTests {

  static final ContentLibrary LIB = ClasspathContent.load(List.of(
      new ContentProperties.BlacklistEntry("pregnancy", List.of("pregnant"))));
  static final long HOUR = 3_600_000L;
  static final long DAY = 24 * HOUR;
  static final List<String> CRONS = List.of("0 9 * * *", "0 11 * * *");

  @TempDir Path dir;
  TestDatabase db;
  WorldEngine engine;
  ScriptWriter writer;

  @BeforeEach
  void world() {
    db = TestDatabase.leased(dir);
    var seed = SkeletonGenerator.generate(LIB, new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
    new SeedImporter(db.sql, db.lane, LIB).importSeed(seed, db.clock.nowMs());
    engine = new WorldEngine(db.sql, db.lane, LIB, Knobs.defaults());
    writer = new ScriptWriter(db.sql, db.lane, engine, ContinuityAuditor.Mode.THROW, db.clock);
  }

  @AfterEach
  void close() {
    db.close();
  }

  static LlmGateway offline() {
    var fakes = new FakeResponses();
    FakeRender.register(fakes);
    return OfflineGateway.create(fakes, app.radiance.kernel.WallClock.system());
  }

  NightlyBatch batch(LlmGateway gateway, LeaseHolder holder) {
    return new NightlyBatch(db.sql, db.admin, db.lane, holder, db.clock, LIB, engine, writer, gateway, "Radiance", CRONS,
        true);
  }

  long count(String sql) {
    return db.sql.queryOne(sql, List.of(), r -> r.lng("n")).orElseThrow();
  }

  @Test
  void aFullNightRendersUnderBudgetAndASecondRunIsANoOp() {
    var meter = new ProjectedMeter(offline(), ProjectedMeter.Pricing.HAIKU, 4.0);
    long t0 = System.nanoTime();
    var first = batch(meter, db.holder).run(NightlyBatch.Kind.NIGHTLY);
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(first.status()).isEqualTo("DONE");
    assertThat(first.planned()).isPositive();
    assertThat(first.rendered()).isPositive();
    assertThat(ms).as("a FULL night in under 5 minutes").isLessThan(5 * 60_000);
    assertThat(meter.usd()).as("projected cost").isLessThanOrEqualTo(0.15);
    assertThat(first.fidelity()).isEqualTo("FULL");
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE body IS NULL AND render_status = 'PLANNED'")).isZero();
    assertThat(count("SELECT COUNT(*) AS n FROM event WHERE importance >= 6 AND recap_line IS NULL AND air_status <> 'DROPPED'"))
        .isZero();
    long posts = count("SELECT COUNT(*) AS n FROM post");
    long calls = meter.totals().values().stream().mapToInt(t -> t.calls).sum();

    var second = batch(meter, db.holder).run(NightlyBatch.Kind.NIGHTLY);
    assertThat(second.status()).isEqualTo("DONE");
    assertThat(second.planned()).isZero();
    assertThat(second.rendered()).isZero();
    assertThat(second.planHash()).isEqualTo(first.planHash());
    assertThat(count("SELECT COUNT(*) AS n FROM post")).isEqualTo(posts);
    assertThat(meter.totals().values().stream().mapToInt(t -> t.calls).sum()).isEqualTo(calls);
    assertThat(count("SELECT COUNT(*) AS n FROM batch_run WHERE status = 'DONE'")).isEqualTo(2);
  }

  /** Regression: the 09:00 run scripted only as far as 11:00's slack, leaving the night's work to the second run. */
  @Test
  void theFirstRunOfTheNightScriptsTheWholeDayAndTheSecondFindsNothingToDo() {
    long now = db.clock.nowMs();
    long nine = now - now % DAY + 9 * HOUR;
    if (nine <= now) nine += DAY;
    var scheduler = new BatchScheduler(batch(offline(), db.holder), db.sql, db.holder, db.clock, false);

    db.clock.set(nine + 53_000);
    assertThat(scheduler.tick()).isTrue();
    long offset = db.sql.queryOne("SELECT time_offset_ms AS o FROM world", List.of(), r -> r.lng("o")).orElseThrow();
    long reach = nine + DAY + 6 * HOUR + offset;
    assertThat(scriptedUntil()).as("past tomorrow's first run, by the slack").isEqualTo(reach);
    long posts = count("SELECT COUNT(*) AS n FROM post");

    db.clock.set(nine + 2 * HOUR + 53_000);
    assertThat(scheduler.tick()).as("the second cron still checks in").isTrue();
    assertThat(scriptedUntil()).isEqualTo(reach);
    assertThat(count("SELECT COUNT(*) AS n FROM post")).isEqualTo(posts);
    assertThat(count("SELECT COUNT(*) AS n FROM batch_run WHERE status = 'DONE' AND items_planned = 0 AND items_rendered = 0"))
        .as("the second run planned and rendered nothing").isEqualTo(1);
    assertThat(scheduler.overdue()).isFalse();
  }

  @Test
  void theNextNightSkipsRunsThatOnlyFinishThisOne() {
    var batch = batch(offline(), db.holder);
    long nine = Instant.parse("2026-11-02T09:00:00Z").toEpochMilli();
    long tomorrow = nine + DAY;
    assertThat(batch.nextNight(nine - 7 * HOUR)).as("before tonight's first run").isEqualTo(nine);
    assertThat(batch.nextNight(nine)).as("at the first run").isEqualTo(tomorrow);
    assertThat(batch.nextNight(nine + HOUR)).as("between the two").isEqualTo(tomorrow);
    assertThat(batch.nextNight(nine + 2 * HOUR)).as("at the second run").isEqualTo(tomorrow);
    assertThat(batch.nextNight(nine + 14 * HOUR)).as("late in the evening").isEqualTo(tomorrow);
  }

  @Test
  void theNextNightOnOtherSchedules() {
    long nine = Instant.parse("2026-11-02T09:00:00Z").toEpochMilli();
    assertThat(withCrons("0 9 * * *").nextNight(nine)).as("one run a night").isEqualTo(nine + DAY);
    assertThat(withCrons("0 9 * * *", "0 21 * * *").nextNight(nine)).as("two far apart are two nights")
        .isEqualTo(nine + 12 * HOUR);
    assertThat(withCrons("0 * * * *").nextNight(nine)).as("no gap: the next run, not a hang").isEqualTo(nine + HOUR);
    assertThat(withCrons().nextNight(nine)).as("no schedule").isEqualTo(nine + DAY);
  }

  NightlyBatch withCrons(String... crons) {
    return new NightlyBatch(db.sql, db.admin, db.lane, db.holder, db.clock, LIB, engine, writer, offline(), "Radiance",
        List.of(crons), true);
  }

  /** Regression: a failed run counted as no run, so a failing batch went again at every one-minute check. */
  @Test
  void aFailedRunWaitsBeforeTryingAgain() {
    var calls = new AtomicInteger();
    var real = offline();
    LlmGateway dying = new Delegating(real) {
      @Override
      public <T> LlmResult<T> call(LlmRequest<T> r) {
        if (r.purpose() == Purpose.RENDER_THREAD && calls.incrementAndGet() > 2) throw new Killed();
        return real.call(r);
      }
    };
    long now = db.clock.nowMs();
    long nine = now - now % DAY + 9 * HOUR;
    if (nine <= now) nine += DAY;
    db.clock.set(nine + 53_000);
    var failing = new BatchScheduler(batch(dying, db.holder), db.sql, db.holder, db.clock, false);
    assertThat(failing.tick()).isTrue();
    assertThat(count("SELECT COUNT(*) AS n FROM batch_run WHERE status = 'FAILED'")).isEqualTo(1);

    db.clock.set(nine + 53_000 + 60_000);
    assertThat(failing.overdue()).as("a minute later").isFalse();
    db.clock.set(nine + 53_000 + BatchScheduler.FAILED_RETRY - 1);
    assertThat(failing.overdue()).as("just before the wait is up").isFalse();

    db.clock.set(nine + 53_000 + BatchScheduler.FAILED_RETRY);
    var healthy = new BatchScheduler(batch(offline(), db.holder), db.sql, db.holder, db.clock, false);
    assertThat(healthy.tick()).as("once the wait is up").isTrue();
    assertThat(count("SELECT COUNT(*) AS n FROM batch_run WHERE status = 'DONE'")).isEqualTo(1);
    assertThat(healthy.overdue()).isFalse();
  }

  long scriptedUntil() {
    return db.sql.queryOne("SELECT scripted_until AS s FROM world", List.of(), r -> r.lng("s")).orElseThrow();
  }

  /** Dies like a killed process: an Error isn't a job failure. */
  static final class Killed extends Error {}

  @Test
  void aRunKilledMidRenderIsCompletedByTheNextWithTheSameScript() {
    var calls = new AtomicInteger();
    var real = offline();
    LlmGateway dying = new Delegating(real) {
      @Override
      public <T> LlmResult<T> call(LlmRequest<T> r) {
        if (r.purpose() == Purpose.RENDER_THREAD && calls.incrementAndGet() > 10) throw new Killed();
        return real.call(r);
      }
    };
    var first = batch(dying, db.holder).run(NightlyBatch.Kind.NIGHTLY);
    assertThat(first.status()).isEqualTo("FAILED");
    long left = count("SELECT COUNT(*) AS n FROM post WHERE body IS NULL AND render_status = 'PLANNED'");
    assertThat(left).isPositive();
    long scriptTo = db.sql.queryOne("SELECT scripted_until AS s FROM world", List.of(), r -> r.lng("s")).orElseThrow();
    String before = writer.storedHash(db.clock.nowMs(), scriptTo);

    var second = batch(offline(), db.holder).run(NightlyBatch.Kind.NIGHTLY);
    assertThat(second.status()).isEqualTo("DONE");
    assertThat(second.planned()).isZero();
    assertThat(second.planHash()).isEqualTo(before);
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE body IS NULL AND render_status = 'PLANNED'")).isZero();
  }

  @Test
  void aTinyDailyBudgetParksRendersUntilTheResetAndNothingIsLostOrRenderedTwice() {
    long reset = db.clock.nowMs() + DAY;
    var calls = new AtomicInteger();
    var seen = ConcurrentHashMap.<String>newKeySet();
    var twice = new AtomicInteger();
    var real = offline();
    LlmGateway budgeted = new Delegating(real) {
      @Override
      public <T> LlmResult<T> call(LlmRequest<T> r) {
        if (r.purpose() == Purpose.RENDER_THREAD) {
          if (db.clock.nowMs() < reset && calls.incrementAndGet() > 8) throw new BudgetExhaustedException("usd:daily", reset);
          String text = r.content().toString();
          int i = text.indexOf("\"briefId\":\"");
          String brief = i < 0 ? text : text.substring(i, Math.min(text.length(), i + 40));
          if (!text.contains("<fix>") && !seen.add(brief)) twice.incrementAndGet();
        }
        return real.call(r);
      }
    };
    var first = batch(budgeted, db.holder).run(NightlyBatch.Kind.NIGHTLY);
    assertThat(first.status()).isEqualTo("PARTIAL");
    assertThat(first.parked()).isPositive();
    assertThat(count("SELECT COUNT(*) AS n FROM job WHERE status = 'WAITING'")).isPositive();

    var scheduler = new BatchScheduler(batch(budgeted, db.holder), db.sql, db.holder, db.clock, false);
    assertThat(scheduler.overdue()).as("nothing can go before the reset").isFalse();
    db.clock.set(reset);
    var second = batch(budgeted, db.holder).run(NightlyBatch.Kind.NIGHTLY);
    assertThat(second.status()).isEqualTo("DONE");
    assertThat(count("SELECT COUNT(*) AS n FROM job WHERE status IN ('WAITING', 'FAILED')")).isZero();
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE body IS NULL AND render_status = 'PLANNED'")).isZero();
    assertThat(twice.get()).as("briefs rendered twice").isZero();
  }

  @Test
  void aOneShotRunDefersToTheLeaseHolderWhichRunsItInProcess() {
    var notHeld = new LeaseHolder();
    var oneShot = batch(offline(), notHeld).run(NightlyBatch.Kind.MANUAL);
    assertThat(oneShot.status()).isEqualTo("DEFERRED");
    assertThat(count("SELECT COUNT(*) AS n FROM batch_run WHERE status = 'DEFERRED'")).isEqualTo(1);

    // the app holds the lease; its scheduler sees the night's batch hasn't run and runs it
    db.clock.set(nextFire(db.clock.nowMs()) + 60_000);
    var scheduler = new BatchScheduler(batch(offline(), db.holder), db.sql, db.holder, db.clock, false);
    assertThat(scheduler.overdue()).isTrue();
    assertThat(scheduler.tick()).isTrue();
    assertThat(count("SELECT COUNT(*) AS n FROM batch_run WHERE status = 'DONE'")).isEqualTo(1);
    assertThat(scheduler.overdue()).isFalse();
  }

  @Test
  void twoSkippedNightsAreBackfilledByTheNextRun() {
    batch(offline(), db.holder).run(NightlyBatch.Kind.NIGHTLY);
    long scripted = db.sql.queryOne("SELECT scripted_until AS s FROM world", List.of(), r -> r.lng("s")).orElseThrow();
    db.clock.set(db.clock.nowMs() + 3 * DAY);
    var r = batch(offline(), db.holder).run(NightlyBatch.Kind.NIGHTLY);
    assertThat(r.status()).isEqualTo("DONE");
    assertThat(r.notes()).anyMatch(n -> n.startsWith("backfilled"));
    long backfilled = db.sql.queryOne("SELECT COUNT(*) AS n FROM post WHERE origin = 'BACKFILL' AND created_at >= ?",
        List.of(scripted), x -> x.lng("n")).orElseThrow();
    assertThat(backfilled).isPositive();
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE air_status = 'AIRED' AND body IS NULL")).isZero();
    long committed = db.sql.queryOne("SELECT committed_until AS c FROM world", List.of(), x -> x.lng("c")).orElseThrow();
    assertThat(committed).isEqualTo(db.clock.nowMs());
  }

  long nextFire(long now) {
    return batch(offline(), db.holder).nextRun(now);
  }

  /** A gateway that passes everything through unless a test overrides {@code call}. */
  static class Delegating implements LlmGateway {
    final LlmGateway d;

    Delegating(LlmGateway d) {
      this.d = d;
    }

    @Override
    public <T> LlmResult<T> call(LlmRequest<T> r) {
      return d.call(r);
    }

    @Override
    public <T> List<LlmResult<T>> callAll(List<LlmRequest<T>> requests) {
      return requests.stream().map(this::call).toList();
    }

    @Override
    public PromptBudget budgetFor(Purpose purpose) {
      return d.budgetFor(purpose);
    }

    @Override
    public int degradationLevel() {
      return 0;
    }
  }
}
