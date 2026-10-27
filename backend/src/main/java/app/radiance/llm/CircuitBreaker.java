package app.radiance.llm;

import app.radiance.kernel.WallClock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per provider (SPEC §5.10): after 5 consecutive failures, skip the provider for 5 minutes. Any answer
 * from the provider — even a refusal — counts as alive.
 */
final class CircuitBreaker {

  static final int THRESHOLD = 5;
  static final long OPEN_MS = 5 * 60_000;

  private record State(int failures, long openUntil) {}

  private final WallClock clock;
  private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

  CircuitBreaker(WallClock clock) {
    this.clock = clock;
  }

  boolean isOpen(String provider) {
    var s = states.get(provider);
    return s != null && s.openUntil() > clock.nowMs();
  }

  void success(String provider) {
    states.remove(provider);
  }

  void failure(String provider) {
    states.compute(provider, (k, s) -> {
      int failures = (s == null ? 0 : s.failures()) + 1;
      return failures >= THRESHOLD
          ? new State(0, clock.nowMs() + OPEN_MS)
          : new State(failures, s == null ? 0 : s.openUntil());
    });
  }
}
