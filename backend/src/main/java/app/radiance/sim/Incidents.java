package app.radiance.sim;

import app.radiance.content.Vocab.Stance;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.RandomSource;
import app.radiance.sim.model.Person;
import app.radiance.sim.model.SimWorld;
import app.radiance.social.ReactionRow;
import app.radiance.story.EventRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Director's incident injector (SPEC §5.16): fire any event def, start any arc, or break a couple up, on the spot, with
 * the fallout scripted over the next hours: friends react and comment. Everything is an ordinary script that airs through the
 * engine, so an injected breakup changes the world exactly as a planned one would (P3).
 */
public final class Incidents {

  /** Thrown for a request that can't happen: an unknown def, a resident who isn't there, a blacklisted arc. */
  public static final class Refused extends RuntimeException {
    public Refused(String message) {
      super(message);
    }
  }

  private static final long MIN = 60_000L;
  private static final long HOUR = 60 * MIN;
  private static final long STARTS_IN = 30_000L;
  private static final int MAX_RESPONDERS = 10;

  /** Two residents who are together. */
  public record Couple(String a, String b) {}

  private final WorldEngine engine;

  public Incidents(WorldEngine engine) {
    this.engine = engine;
  }

  /** Everyone who could break up right now. */
  public List<Couple> couples() {
    return engine.locked(w -> w.residents().stream().filter(p -> p.partnerId != null && p.id.compareTo(p.partnerId) < 0
        && w.people.containsKey(p.partnerId) && !w.people.get(p.partnerId).user).map(p -> new Couple(p.id, p.partnerId)).toList());
  }

  /** Fires one event def for {@code actor}, with {@code other} as its other party if it has one. */
  public Script event(String defId, String actor, String other, long now) {
    return engine.locked(w -> {
      var def = engine.lib().events().get(defId);
      if (def == null) throw new Refused("No event called " + defId);
      var a = resident(w, actor);
      var participants = new TreeMap<String, String>();
      participants.put("actor", a.id);
      if (!def.participants().isEmpty()) {
        if (other == null) throw new Refused(defId + " needs someone else in it");
        if (other.equals(actor)) throw new Refused("It takes two");
        participants.put(def.participants().keySet().iterator().next(), resident(w, other).id);
      }
      var copy = w.copy();
      var plan = plan(copy, now);
      new Planner(engine.lib(), engine.knobs()).fire(plan, def, copy.person(a.id), participants, now + STARTS_IN);
      fallout(plan, now);
      return directed(plan.script);
    });
  }

  /** Starts an arc at its first phase. */
  public Script arc(String defId, String subject, String other, long now) {
    return engine.locked(w -> {
      var arcDef = engine.lib().arcs().get(defId);
      if (arcDef == null) throw new Refused("No arc called " + defId);
      if (engine.lib().isBlacklisted(defId)) throw new Refused(defId + " is blacklisted");
      var a = resident(w, subject);
      var b = other == null ? null : resident(w, other);
      if (b != null && b.id.equals(a.id)) throw new Refused("It takes two");
      if (arcDef.roles().size() > 1 && b == null) throw new Refused(defId + " needs someone else in it");
      if ((defId.equals("romance") || defId.equals("affair")) && a.gender == b.gender) throw new Refused("Romance here is between a man and a woman");
      if (w.arcsOf(a.id).stream().anyMatch(x -> x.defId.equals(defId) && x.active())) throw new Refused(a.name + " is already in one of those");
      var copy = w.copy();
      var plan = plan(copy, now);
      ArcEngine.start(plan, defId, a.id, b == null ? null : b.id, now + STARTS_IN, "the director");
      fallout(plan, now);
      return directed(plan.script);
    });
  }

  /**
   * Breaks up two residents who are together (SPEC §12 M13): their romance moves to its breakup phase, starting one
   * first if they got together before the world began. Status, tags and the aftermath arcs come from the phase's own effects.
   */
  public Script breakup(String first, String second, long now) {
    return engine.locked(w -> {
      var a = resident(w, first);
      var b = resident(w, second);
      if (a.id.equals(b.id)) throw new Refused("It takes two");
      if (engine.lib().isBlacklisted("romance")) throw new Refused("romance is blacklisted");
      var copy = w.copy();
      var plan = plan(copy, now);
      var def = engine.lib().arcs().get("romance");
      var arc = activeRomance(copy, a.id, b.id);
      long at = now + STARTS_IN;
      if (arc == null && !a.id.equals(b.partnerId)) throw new Refused("They aren't together");
      if (arc == null) {
        var man = a.gender.name().equals("M") ? a : b;
        var woman = man == a ? b : a;
        ArcEngine.start(plan, "romance", man.id, woman.id, at - 1_000L, "the director");
        var ctx = new Airing.Ctx(engine.lib(), engine.knobs(), null, false, Airing.Lookup.NONE);
        Airing.air(copy, plan.script.entries(), ctx);
        arc = activeRomance(copy, a.id, b.id);
        if (arc == null) throw new Refused("Couldn't find what's between them");
      }
      ArcEngine.jump(plan, arc, def, "breakup", at);
      fallout(plan, now);
      return directed(plan.script);
    });
  }

  /** The log says who really did it. */
  private static Script directed(Script s) {
    s.events.replaceAll(e -> e.withSource(EventRow.Source.DIRECTOR));
    return s;
  }

  private static app.radiance.sim.model.Arc activeRomance(SimWorld w, String a, String b) {
    return w.arcs.values().stream().filter(x -> x.active() && "romance".equals(x.defId) && x.involves(a) && x.involves(b)).findFirst().orElse(null);
  }

  private Plan plan(SimWorld w, long now) {
    return new Plan(w, engine.lib(), engine.knobs(), new Slice(now, now + 6 * HOUR, Fidelity.FULL),
        new Plan.Inputs(List.of(), null, Set.of(), Map.of(), ContentOrigin.LIVE), new HashMap<>());
  }

  private static Person resident(SimWorld w, String id) {
    var p = id == null ? null : w.people.get(id);
    if (p == null || p.user) throw new Refused("No resident with that id");
    return p;
  }

  /** People close to whoever posted notice: reactions and comments spread over the next hours, closest first. */
  private void fallout(Plan plan, long now) {
    var lib = engine.lib();
    var k = engine.knobs();
    var w = plan.w;
    String day = w.day(now);
    for (var post : List.copyOf(plan.script.posts)) {
      var item = ModelStore.postItem(post);
      var rng = RandomSource.seeded(w.seed).derive("fallout:" + post.id());
      var author = w.person(post.authorId());
      var around = new ArrayList<>(w.residents().stream().filter(b -> !b.id.equals(author.id)).filter(b -> {
        var e = w.edgeOrNull(b.id, author.id);
        return e != null && e.connected;
      }).toList());
      around.sort(Comparator.comparingDouble((Person b) -> -w.edge(b.id, author.id).familiarity).thenComparing(b -> b.id));
      int count = 0;
      for (var b : around) {
        if (count++ >= MAX_RESPONDERS) break;
        double aff = w.edge(b.id, author.id).affinity;
        long at = post.createdAt() + LiveResponder.delay(rng, 40 * MIN, 5 * HOUR);
        var stance = Stances.comment(w, b, author, item, lib, k, day, rng);
        plan.reaction(at, ReactionRow.Target.POST, post.id(), b.id, Stances.reaction(stance == Stance.REACT_ONLY ? null : stance, b, item, aff, rng));
        if (stance != Stance.REACT_ONLY && rng.chance(0.6)) {
          plan.comment("fallout:" + b.id + ":" + post.id(), at + 2 * MIN, post.id(), null, b.id, Planner.commentBrief(item, stance, author.id, null));
        }
      }
    }
  }
}
