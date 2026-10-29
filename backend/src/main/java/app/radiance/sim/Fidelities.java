package app.radiance.sim;

import app.radiance.kernel.Fidelity;
import java.util.ArrayList;
import java.util.List;

/**
 * Fidelity and backfill (SPEC §5.1): which fidelity a script is written at from R's idle time, how a
 * range splits into slices (1 h FULL, 6 h REDUCED, 24 h SUMMARY), and how a gap is backfilled by age.
 * A null fidelity is structure only: arcs and drift advance, nothing gets a post.
 */
public final class Fidelities {

  private Fidelities() {}

  static final long HOUR = 3_600_000L;
  static final long DAY = 24 * HOUR;

  public static Fidelity forIdle(Long lastSessionAt, long now, Knobs k) {
    if (lastSessionAt == null) return Fidelity.FULL;
    double days = (now - lastSessionAt) / (double) DAY;
    if (days < k.idleReducedAfterDays()) return Fidelity.FULL;
    if (days < k.idleSummaryAfterDays()) return Fidelity.REDUCED;
    if (days < k.pauseRenderingAfterDays()) return Fidelity.SUMMARY;
    return null;
  }

  static long sliceLength(Fidelity f) {
    if (f == null) return DAY;
    return switch (f) {
      case FULL -> HOUR;
      case REDUCED -> 6 * HOUR;
      case SUMMARY -> DAY;
    };
  }

  /** {@code [from, to)} in slices of one fidelity, aligned to slice boundaries in UTC. */
  public static List<Slice> slices(long from, long to, Fidelity f) {
    var out = new ArrayList<Slice>();
    long len = sliceLength(f);
    long t = from;
    while (t < to) {
      long end = Math.min(to, t - Math.floorMod(t, len) + len);
      out.add(new Slice(t, end, f));
      t = end;
    }
    return out;
  }

  /** Structure only (idle more than {@code pause-rendering-after}): arcs and drift advance, nothing posts. */
  public static List<Slice> structure(long from, long to) {
    return slices(from, to, null);
  }

  /**
   * A gap {@code [from, now)} planned by age (SPEC §5.1): FULL for the last {@code backfill.full-window},
   * REDUCED up to {@code reduced-window}, SUMMARY up to {@code max-gap}, and one structure-only stretch
   * (the TIME_SKIP) beyond. Chronological, because state flows forward.
   */
  public static List<Slice> backfill(long from, long now, Knobs k) {
    long full = now - (long) (k.backfillFullHours() * HOUR);
    long reduced = now - (long) (k.backfillReducedDays() * DAY);
    long summary = now - (long) (k.maxGapDays() * DAY);
    var out = new ArrayList<Slice>();
    if (from < summary) out.add(new Slice(from, summary, null));
    out.addAll(slices(Math.max(from, summary), Math.max(from, reduced), Fidelity.SUMMARY));
    out.addAll(slices(Math.max(from, reduced), Math.max(from, full), Fidelity.REDUCED));
    out.addAll(slices(Math.max(from, full), now, Fidelity.FULL));
    out.removeIf(s -> s.end() <= s.start());
    return out;
  }
}
