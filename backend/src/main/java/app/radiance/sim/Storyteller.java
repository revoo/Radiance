package app.radiance.sim;

import app.radiance.kernel.RandomSource;

/**
 * The storyteller (SPEC §5.4, RimWorld-lite): a target tension curve per profile, and the weight
 * multiplier LIFE and DRAMA defs get from {@code target − tension}, so drama clusters near the peaks.
 */
public final class Storyteller {

  private Storyteller() {}

  static final double DAY = 86_400_000.0;

  /** Target tension, 0–100, at world time {@code t}. */
  public static double target(String profile, long seed, long t) {
    double days = t / DAY;
    return switch (profile) {
      case "CHAOTIC" -> {
        long day = (long) Math.floor(days);
        yield 15 + 70 * RandomSource.seeded(seed).derive("storyteller:chaotic:" + day).nextDouble();
      }
      case "COZY" -> 15 + 10 * Math.sin(2 * Math.PI * days / 14.0);
      default -> 40 + 30 * Math.sin(2 * Math.PI * days / 7.0);
    };
  }

  /** The LIFE/DRAMA weight multiplier: above 1 when the world is calmer than the curve wants. */
  public static double factor(String profile, long seed, long t, double tension, double dramaIntensity) {
    double f = Math.exp((target(profile, seed, t) - tension) / 30.0) * dramaIntensity;
    return Math.max(0.2, Math.min(3.0, f));
  }
}
