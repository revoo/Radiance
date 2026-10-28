package app.radiance.sim;

import app.radiance.content.Selector;
import app.radiance.graph.RelationshipRow.Kind;
import app.radiance.kernel.RandomSource;
import app.radiance.sim.model.Edge;
import app.radiance.sim.model.Person;
import app.radiance.sim.model.SimWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Evaluates a participant selector (SPEC §5.4) for an actor: {@code friend[affinity>40]},
 * {@code circle:softball[familiarity>=20]}, {@code user[attraction>40]}. Conditions read the actor's
 * edge toward the candidate; a candidate is drawn weighted by familiarity.
 */
public final class Selectors {

  private Selectors() {}

  public static List<Person> candidates(SimWorld w, Person actor, Selector s, Predicate<Person> extra) {
    var out = new ArrayList<Person>();
    for (var c : w.people.values()) {
      if (c.id.equals(actor.id) || !extra.test(c)) continue;
      boolean user = c.user;
      if (s.relation().equals("user") != user) continue;
      var e = w.edgeOrNull(actor.id, c.id);
      if (!relation(w, actor, c, e, s)) continue;
      if (!conditions(e, s)) continue;
      out.add(c);
    }
    return out;
  }

  public static Person pick(SimWorld w, Person actor, Selector s, Predicate<Person> extra, RandomSource rng) {
    var cs = candidates(w, actor, s, extra);
    if (cs.isEmpty()) return null;
    double total = 0;
    var weights = new double[cs.size()];
    for (int i = 0; i < cs.size(); i++) {
      var e = w.edgeOrNull(actor.id, cs.get(i).id);
      weights[i] = 1 + (e == null ? 0 : e.familiarity);
      total += weights[i];
    }
    double x = rng.nextDouble() * total;
    for (int i = 0; i < weights.length; i++) {
      x -= weights[i];
      if (x < 0) return cs.get(i);
    }
    return cs.getLast();
  }

  static boolean relation(SimWorld w, Person a, Person c, Edge e, Selector s) {
    return switch (s.relation()) {
      case "anyone", "user" -> true;
      case "friend" -> e != null && (e.kind == Kind.FRIEND || e.kind == Kind.CLOSE_FRIEND || (e.connected
          && e.kind != Kind.STRANGER && e.kind != Kind.RIVAL && e.kind != Kind.EX));
      case "close_friend" -> e != null && e.kind == Kind.CLOSE_FRIEND;
      case "best_friend" -> e != null && e.hasTag("best_friend");
      case "family" -> e != null && e.kind == Kind.FAMILY;
      case "coworker" -> e != null && e.kind == Kind.COWORKER;
      case "partner" -> c.id.equals(a.partnerId);
      case "ex" -> e != null && e.kind == Kind.EX;
      case "rival" -> e != null && (e.kind == Kind.RIVAL || e.hasTag("debate_rival") || e.hasTag("feud"));
      case "crush" -> e != null && e.hasTag("crush");
      case "circle" -> a.circles.contains(s.param()) && c.circles.contains(s.param());
      case "neighbor" -> a.circles.contains("berwyn-block") && c.circles.contains("berwyn-block");
      case "connected" -> e != null && e.connected;
      case "stranger" -> e == null || e.kind == Kind.STRANGER;
      default -> false;
    };
  }

  static boolean conditions(Edge e, Selector s) {
    for (var c : s.conditions()) {
      double v = e == null ? (c.dimension().equals("trust") ? 30 : 0) : e.get(c.dimension());
      boolean ok = switch (c.op()) {
        case ">" -> v > c.value();
        case ">=" -> v >= c.value();
        case "<" -> v < c.value();
        default -> v <= c.value();
      };
      if (!ok) return false;
    }
    return true;
  }
}
