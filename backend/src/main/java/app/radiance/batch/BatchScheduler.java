package app.radiance.batch;

import app.radiance.kernel.WallClock;
import app.radiance.persistence.LeaseHolder;
import app.radiance.persistence.Sql;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the nightly batch in-process (SPEC §5.1, §6 M6): at each {@code radiance.batch.crons} time, at
 * startup if a run was missed (overdue detection), and whenever a one-shot run deferred to this process
 * because it holds the lease. The second cron of the night finishes a PARTIAL run or does nothing. Only
 * the lease holder runs it; the batch itself runs on a virtual thread so the app keeps serving.
 */
public final class BatchScheduler implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(BatchScheduler.class);

  private final NightlyBatch batch;
  private final Sql sql;
  private final LeaseHolder lease;
  private final WallClock clock;
  private final boolean enabled;
  private ScheduledExecutorService scheduler;

  public BatchScheduler(NightlyBatch batch, Sql sql, LeaseHolder lease, WallClock clock, boolean enabled) {
    this.batch = batch;
    this.sql = sql;
    this.lease = lease;
    this.clock = clock;
    this.enabled = enabled;
  }

  /**
   * True when a scheduled time has passed with no finished run since it, or a PARTIAL run's parked
   * renders have come due.
   */
  public boolean overdue() {
    long now = clock.nowMs();
    long last = batch.lastRun(now);
    if (last == Long.MIN_VALUE) return false;
    var since = sql.query("SELECT status FROM batch_run WHERE started_at >= ? AND status IN ('RUNNING', 'DONE', 'PARTIAL')"
        + " ORDER BY started_at DESC", List.of(last), r -> r.str("status"));
    if (since.isEmpty()) return true;
    if (since.contains("RUNNING") || since.contains("DONE")) return false;
    // only PARTIAL runs since the last fire: resume once a parked render can go
    return sql.queryOne("SELECT COUNT(*) AS n FROM job WHERE status IN ('WAITING', 'PENDING') AND run_after <= ?"
        + " AND kind IN ('RENDER_THREAD', 'RENDER_DM')", List.of(now), r -> r.lng("n")).orElse(0L) > 0;
  }

  /** One check: runs the batch now (on this thread) if it's due and this process holds the lease. */
  public boolean tick() {
    if (!lease.isHeld() || batch.isRunning() || !overdue()) return false;
    log.info("Nightly batch is due; running it in-process");
    batch.run(NightlyBatch.Kind.NIGHTLY);
    return true;
  }

  @Override
  public synchronized void start() {
    if (!enabled || scheduler != null) return;
    scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("nightly-batch").factory());
    scheduler.scheduleWithFixedDelay(() -> {
      try {
        tick();
      } catch (RuntimeException e) {
        log.warn("Nightly batch check failed", e);
      }
    }, 30, 60, TimeUnit.SECONDS);
  }

  @Override
  public synchronized void stop() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return scheduler != null;
  }
}
