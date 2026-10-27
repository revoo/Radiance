package app.radiance.persistence;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Keeps the writer lease while the app runs: tries to acquire it until it can, then renews it, and
 * releases it on shutdown so a restart or a batch process can take it at once. Reloading the world
 * model on acquisition arrives with the WorldEngine (M4).
 */
public final class LeaseKeeper implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(LeaseKeeper.class);

  private final WriterLease lease;
  private final LeaseHolder holder;
  private final Duration interval;
  private ScheduledExecutorService scheduler;

  public LeaseKeeper(WriterLease lease, LeaseHolder holder, Duration interval) {
    this.lease = lease;
    this.holder = holder;
    this.interval = interval;
  }

  void tick() {
    try {
      boolean had = holder.isHeld();
      boolean has = had ? lease.renew() : lease.tryAcquire();
      if (has && !had) {
        log.info("Writer lease acquired by {} (epoch {})", lease.owner(),
            holder.current().map(LeaseHolder.Fence::epoch).orElse(-1L));
      } else if (!has && had) {
        log.warn("Writer lease lost by {}; another process is writing the world", lease.owner());
      }
    } catch (RuntimeException e) {
      log.warn("Writer lease check failed", e);
    }
  }

  @Override
  public synchronized void start() {
    if (scheduler != null) return;
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, "writer-lease");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(this::tick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    if (scheduler == null) return;
    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    scheduler = null;
    try {
      lease.release();
    } catch (RuntimeException e) {
      log.warn("Releasing the writer lease failed; it will expire on its own", e);
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return scheduler != null;
  }
}
