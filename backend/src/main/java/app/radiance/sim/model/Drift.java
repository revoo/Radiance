package app.radiance.sim.model;

import app.radiance.kernel.RandomSource;

/**
 * Mood, energy, stress and partner satisfaction follow an Ornstein–Uhlenbeck process toward each
 * persona's baselines (SPEC §5.2), stepped with the exact discretization, so one step of three weeks
 * is distributed exactly like many small ones.
 */
public final class Drift {

  private Drift() {}

  static final double HOUR = 3_600_000.0;

  /** x(t+Δ) = μ + (x − μ)e^(−θΔ) + σ·sqrt((1 − e^(−2θΔ)) / 2θ)·z, with Δ in hours. */
  public static double step(double x, double mu, double theta, double sigma, double dtHours, double z) {
    if (dtHours <= 0) return x;
    double decay = Math.exp(-theta * dtHours);
    return mu + (x - mu) * decay + sigma * Math.sqrt((1 - decay * decay) / (2 * theta)) * z;
  }

  /** θ for a half-life in hours. */
  public static double theta(double halfLifeHours) {
    return Math.log(2) / halfLifeHours;
  }

  /** σ that gives a stationary standard deviation {@code sd} at rate θ. */
  public static double sigma(double sd, double theta) {
    return sd * Math.sqrt(2 * theta);
  }

  /** The persona's resting mood, −1…1, from optimism and neuroticism. */
  public static double moodBaseline(Person p) {
    return Meters.clamp(0.1 + (p.trait("optimism") - 50) / 250 - (p.trait("neuroticism") - 50) / 250, -0.6, 0.6);
  }

  public static double stressBaseline(Person p) {
    return Meters.clamp(0.25 + (p.trait("neuroticism") - 50) / 300, 0.05, 0.6);
  }

  /**
   * Brings {@code p}'s continuous state from {@code stateAt} to {@code now}. Noise comes from a stream
   * keyed by the actor and the target time, so the same step always draws the same values.
   */
  public static void advance(Person p, long seed, double moodHalfLifeHours, long now) {
    if (p.user || now <= p.stateAt) return;
    double dt = (now - p.stateAt) / HOUR;
    var rng = RandomSource.forActor(seed, p.id, now).derive("drift");
    double th = theta(moodHalfLifeHours);
    p.mood = Meters.clamp(step(p.mood, moodBaseline(p), th, sigma(0.25, th), dt, rng.nextGaussian()), -1, 1);
    p.energy = Meters.clamp(step(p.energy, 0.6, th, sigma(0.15, th), dt, rng.nextGaussian()), 0, 1);
    p.stress = Meters.clamp(step(p.stress, stressBaseline(p), th, sigma(0.12, th), dt, rng.nextGaussian()), 0, 1);
    if (p.partnerSatisfaction != null) {
      double slow = theta(7 * 24);
      p.partnerSatisfaction = Meters.clamp(step(p.partnerSatisfaction, 0.7, slow, sigma(0.1, slow), dt,
          rng.nextGaussian()), 0, 1);
    }
    // Trait offsets fade back toward the baseline (half-life ≈ 60 days); event nudges add to them.
    double fade = Math.pow(0.5, dt / (60 * 24));
    p.traitOffsets.replaceAll((k, v) -> v * fade);
    p.stateAt = now;
    p.dirtyState = true;
  }
}
