package app.radiance.kernel;

/**
 * Real (wall-clock) time in epoch milliseconds UTC. Operational state runs on it: jobs, budget windows,
 * LLM call logs, batch runs and the writer lease. World time is derived from it plus the world's offset
 * (see {@code world.WorldClock}); the two must never be mixed (ADR 0010).
 */
@FunctionalInterface
public interface WallClock {

  long nowMs();

  static WallClock system() {
    return System::currentTimeMillis;
  }
}
