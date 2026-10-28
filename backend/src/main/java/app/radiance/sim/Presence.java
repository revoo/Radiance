package app.radiance.sim;

import app.radiance.content.Rules.EngagementRules;
import app.radiance.kernel.RandomSource;
import app.radiance.sim.model.Person;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * When residents are online (SPEC §5.2, §5.8): 3–10 sessions a day of 5–20 minutes, sampled from the
 * routine's hourly intensity curve. Hours R is usually online get {@code prime-time-boost} more weight,
 * so scripts concentrate activity where he'll see it. A pure function of the seed, actor and date.
 */
public final class Presence {

  private Presence() {}

  public record Session(long start, long end) {}

  public static boolean weekend(LocalDate d) {
    return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
  }

  /** Hour weights for {@code p} on {@code d}, with R's prime hours boosted. */
  public static double[] curve(Person p, LocalDate d, Set<Integer> primeHours, double boost) {
    var base = p.routine.curve(weekend(d));
    var out = new double[24];
    for (int h = 0; h < 24; h++) out[h] = Math.max(0, base[h]) * (primeHours.contains(h) ? boost : 1);
    return out;
  }

  public static List<Session> sessions(Person p, LocalDate d, ZoneId zone, long seed, EngagementRules rules,
      Set<Integer> primeHours, double boost) {
    var out = new ArrayList<Session>();
    if (p.user) return out;
    long dayStart = d.atStartOfDay(zone).toInstant().toEpochMilli();
    var rng = RandomSource.forActor(seed, p.id, dayStart).derive("sessions");
    var w = curve(p, d, primeHours, boost);
    double total = 0;
    for (double x : w) total += x;
    if (total <= 0) return out;
    double busy = Math.min(1, total / 8.0);
    int n = (int) Math.round(rules.sessionsPerDayMin() + (rules.sessionsPerDayMax() - rules.sessionsPerDayMin()) * busy);
    for (int i = 0; i < n; i++) {
      int hour = pick(w, total, rng.nextDouble());
      long start = dayStart + hour * 3_600_000L + rng.nextInt(60) * 60_000L;
      int minutes = rules.sessionMinutesMin() + rng.nextInt(rules.sessionMinutesMax() - rules.sessionMinutesMin() + 1);
      out.add(new Session(start, start + minutes * 60_000L));
    }
    out.sort(java.util.Comparator.comparingLong(Session::start));
    return out;
  }

  /** A moment in [from, to) drawn from the hour curve, for posts; null if the curve is flat zero there. */
  public static Long momentIn(double[] w, long dayStart, long from, long to, RandomSource rng) {
    double total = 0;
    var weights = new double[24];
    for (int h = 0; h < 24; h++) {
      long hs = dayStart + h * 3_600_000L;
      long lo = Math.max(hs, from), hi = Math.min(hs + 3_600_000L, to);
      if (hi > lo) weights[h] = w[h] * (hi - lo) / 3_600_000.0;
      total += weights[h];
    }
    if (total <= 0) return null;
    int h = pick(weights, total, rng.nextDouble());
    long hs = dayStart + h * 3_600_000L;
    long lo = Math.max(hs, from), hi = Math.min(hs + 3_600_000L, to);
    return lo + (long) (rng.nextDouble() * (hi - lo));
  }

  /** The share of the day's weight that falls in [from, to). */
  public static double share(double[] w, long dayStart, long from, long to) {
    double total = 0, in = 0;
    for (int h = 0; h < 24; h++) {
      total += w[h];
      long hs = dayStart + h * 3_600_000L;
      long lo = Math.max(hs, from), hi = Math.min(hs + 3_600_000L, to);
      if (hi > lo) in += w[h] * (hi - lo) / 3_600_000.0;
    }
    return total <= 0 ? 0 : in / total;
  }

  static int pick(double[] w, double total, double u) {
    double x = u * total;
    for (int i = 0; i < w.length; i++) {
      x -= w[i];
      if (x < 0) return i;
    }
    for (int i = w.length - 1; i >= 0; i--) if (w[i] > 0) return i;
    return 0;
  }
}
