package app.radiance.kernel;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** A wall clock that only moves when told to. For tests and the simulation harness. */
public final class ManualClock implements WallClock {

  private final AtomicLong now;

  public ManualClock(long startMs) {
    this.now = new AtomicLong(startMs);
  }

  @Override
  public long nowMs() {
    return now.get();
  }

  public void set(long ms) {
    now.set(ms);
  }

  public void advance(Duration d) {
    now.addAndGet(d.toMillis());
  }
}
