package app.radiance.sim.model;

import app.radiance.content.Rules.Deltas;
import app.radiance.content.Rules.RelationshipRules;
import app.radiance.content.Rules.TemperamentRules;
import app.radiance.graph.RelationshipRow.Kind;

/**
 * Like-meter math (SPEC §5.2, §5.3): affinity deltas through the receiver's temperament and mood with
 * tapering bounds, and the exponential relaxation every edge drifts by between interactions.
 */
public final class Meters {

  private Meters() {}

  static final double DAY = 86_400_000.0;

  /**
   * One raw affinity change as the receiver feels it.
   *
   * @param arcEvent only arc events (a feud, a betrayal) may push a meter past its temperament bound
   */
  public static double affinity(double current, double raw, Person.Temper t, double mood, TemperamentRules rules,
      boolean arcEvent) {
    if (raw == 0) return current;
    double d = raw * (raw > 0 ? t.goodGain() : t.badGain());
    if (mood < 0) d *= raw < 0 ? 1 + rules.moodEffect() * -mood : 1 - rules.moodEffect() * -mood;
    if (!arcEvent) {
      double taper = rules.taperZone();
      if (d > 0) {
        double room = t.ceiling() - current;
        if (room <= 0) return current;
        if (room < taper) d *= room / taper;
        d = Math.min(d, room);
      } else {
        double room = current - t.floor();
        if (room <= 0) return current;
        if (room < taper) d *= room / taper;
        d = Math.max(d, -room);
      }
    }
    return clamp(current + d, -100, 100);
  }

  /** Applies one rule-table delta to {@code e} (the receiver's view of the actor), at time {@code now}. */
  public static void apply(Edge e, Deltas d, Person receiver, TemperamentRules rules, boolean arcEvent, long now) {
    e.affinity = affinity(e.affinity, d.affinity(), receiver.user ? Person.Temper.NEUTRAL : receiver.temper,
        receiver.mood, rules, arcEvent);
    e.trust = clamp(e.trust + d.trust(), 0, 100);
    e.attraction = clamp(e.attraction + d.attraction(), 0, 100);
    e.familiarity = clamp(e.familiarity + d.familiarity(), 0, 100);
    e.respect = clamp(e.respect + d.respect(), -100, 100);
    e.lastInteraction = now;
    e.dirty = true;
  }

  /**
   * Brings {@code e} from its {@code settledAt} to {@code now}: familiarity decays toward 0 and affinity
   * relaxes toward its kind's baseline plus the viewer's temperament baseline. Exponential, so any
   * split of the interval gives the same result.
   */
  public static void settle(Edge e, Person viewer, RelationshipRules rules, double affinityHalfLifeDays, long now) {
    if (now <= e.settledAt) return;
    double days = (now - e.settledAt) / DAY;
    e.familiarity *= Math.pow(0.5, days / rules.familiarityHalfLifeDays());
    if (!viewer.user) {
      double base = baseline(e.kind, viewer, rules);
      e.affinity = base + (e.affinity - base) * Math.pow(0.5, days / affinityHalfLifeDays);
    }
    e.settledAt = now;
    e.dirty = true;
  }

  public static double baseline(Kind kind, Person viewer, RelationshipRules rules) {
    double b = rules.kindBaselines().getOrDefault(kind.name(), 0.0) + viewer.temper.baseline();
    return clamp(b, viewer.temper.floor(), viewer.temper.ceiling());
  }

  /** ACQUAINTANCE ↔ FRIEND by the numbers; romantic and family kinds change only through arcs. */
  public static void reconsiderKind(Edge e, Person viewer, RelationshipRules rules) {
    double over = e.affinity - viewer.temper.baseline();
    if ((e.kind == Kind.STRANGER || e.kind == Kind.ACQUAINTANCE)
        && e.familiarity > rules.friendFamiliarityMin() && over > rules.friendAffinityAboveBaseline()) {
      e.kind = Kind.FRIEND;
      e.dirty = true;
    } else if (e.kind == Kind.STRANGER && e.familiarity > 10) {
      e.kind = Kind.ACQUAINTANCE;
      e.dirty = true;
    }
  }

  public static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
