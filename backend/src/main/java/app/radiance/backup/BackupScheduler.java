package app.radiance.backup;

import app.radiance.kernel.WallClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.support.CronExpression;

/**
 * Takes the nightly backup in-process (ADR 0019): at {@code radiance.backup.cron}, and soon after startup
 * if one was missed, or if the last one couldn't reach R2 and now it can (so adding keys and restarting
 * uploads right away). A failed run tries again after an hour.
 */
public final class BackupScheduler implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);
  static final long RETRY_MS = Duration.ofHours(1).toMillis();

  private final BackupService backups;
  private final CronExpression cron;
  private final WallClock clock;
  private final boolean enabled;
  private ScheduledExecutorService scheduler;

  public BackupScheduler(BackupService backups, String unixCron, WallClock clock, boolean enabled) {
    this.backups = backups;
    this.cron = CronExpression.parse("0 " + unixCron);
    this.clock = clock;
    this.enabled = enabled;
  }

  /** The last scheduled time at or before {@code now}. */
  long lastFire(long now) {
    var t = Instant.ofEpochMilli(now).minus(Duration.ofDays(2)).atZone(ZoneOffset.UTC);
    long last = Long.MIN_VALUE;
    while (true) {
      var next = cron.next(t);
      if (next == null || next.toInstant().toEpochMilli() > now) return last;
      last = next.toInstant().toEpochMilli();
      t = next;
    }
  }

  /** A backup is due: none since the last scheduled time, or none has reached R2 since it and now one can. */
  boolean due(long now) {
    var s = backups.status();
    if (s.lastAttemptAt() != null && now - s.lastAttemptAt() < RETRY_MS && s.lastError() != null) return false;
    long fire = lastFire(now);
    if (s.lastAt() == null || s.lastAt() < fire) return true;
    return backups.offsiteConfigured() && (s.lastOffsiteAt() == null || s.lastOffsiteAt() < fire);
  }

  /** One check: backs up now (on this thread) if it's due. */
  public boolean tick() {
    if (!due(clock.nowMs())) return false;
    backups.run();
    return true;
  }

  @Override
  public synchronized void start() {
    if (!enabled || scheduler != null) return;
    String banner = backups.missingKeysBanner();
    if (banner != null) log.error("{}", banner);
    scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("nightly-backup").factory());
    scheduler.scheduleWithFixedDelay(() -> {
      try {
        tick();
      } catch (RuntimeException e) {
        log.error("Backup check failed", e);
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
