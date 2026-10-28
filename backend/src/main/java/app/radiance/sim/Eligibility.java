package app.radiance.sim;

import app.radiance.content.ContentLibrary;
import app.radiance.content.Defs.Conditions;
import app.radiance.content.Defs.EventDef;
import app.radiance.sim.model.Person;
import app.radiance.sim.model.SimWorld;
import app.radiance.sim.model.Tag;
import app.radiance.weather.WeatherBook;
import java.util.Set;

/**
 * Whether an event def's conditions hold for an actor at a moment (SPEC §5.4, §5.5). The planner uses
 * it as a hard filter (invariant 3: no contradictions) and airing re-checks it against the committed
 * world (invariant 8), weather included.
 */
public final class Eligibility {

  private Eligibility() {}

  /** Everything but the calendar and seasons, which don't change between scripting and airing. */
  public static boolean holds(EventDef def, Person actor, SimWorld world, ContentLibrary lib, WeatherBook weather,
      long t) {
    var tags = world.activeTags(actor.id, t, lib);
    if (!requires(def.requires(), actor, tags, world)) return false;
    if (excludes(def.excludes(), actor, tags, world)) return false;
    if (blocked(def, tags, lib)) return false;
    if (def.when() != null && def.when().weather() != null) {
      if (weather == null || !weather.holds(def.when().weather(), world.date(t))) return false;
    }
    return true;
  }

  /** A constraint's planner semantics: its tag excludes the def's tags or blocks its category. */
  public static boolean blocked(EventDef def, Set<String> tags, ContentLibrary lib) {
    for (String tag : tags) {
      var c = lib.constraints().get(Tag.base(tag));
      if (c == null) continue;
      if (c.planner().blocksCategories().contains(def.category())) return true;
      for (String et : def.tags()) if (c.planner().excludesEventTags().contains(et)) return true;
    }
    return false;
  }

  static boolean requires(Conditions c, Person p, Set<String> tags, SimWorld w) {
    for (String t : c.tags()) if (!hasTag(tags, t)) return false;
    for (String a : c.arcs()) if (!inArc(w, p, a)) return false;
    for (String q : c.quirks()) if (!p.hasQuirk(q)) return false;
    if (!c.relationshipStatus().isEmpty() && !c.relationshipStatus().contains(p.status)) return false;
    for (var kind : c.entities()) if (!owns(w, p, kind)) return false;
    if (!c.traditions().isEmpty() && (p.faith == null || c.traditions().stream().noneMatch(t -> faith(p, t)))) return false;
    return true;
  }

  /** True if any listed condition holds: one is enough to exclude. */
  static boolean excludes(Conditions c, Person p, Set<String> tags, SimWorld w) {
    for (String t : c.tags()) if (hasTag(tags, t)) return true;
    for (String a : c.arcs()) if (inArc(w, p, a)) return true;
    for (String q : c.quirks()) if (p.hasQuirk(q)) return true;
    if (c.relationshipStatus().contains(p.status)) return true;
    for (var kind : c.entities()) if (owns(w, p, kind)) return true;
    for (String t : c.traditions()) if (faith(p, t)) return true;
    return false;
  }

  /** {@code injured} matches {@code injured:knee}; {@code injured:knee} matches only itself. */
  public static boolean hasTag(Set<String> tags, String wanted) {
    for (String t : tags) if (t.equals(wanted) || (!wanted.contains(":") && Tag.base(t).equals(wanted))) return true;
    return false;
  }

  /** {@code car_saga} or {@code car_saga:neglect}. */
  static boolean inArc(SimWorld w, Person p, String spec) {
    int i = spec.indexOf(':');
    String def = i < 0 ? spec : spec.substring(0, i);
    String phase = i < 0 ? null : spec.substring(i + 1);
    return w.arcsOf(p.id).stream().anyMatch(a -> a.defId.equals(def) && (phase == null || a.phase.equals(phase)));
  }

  static boolean owns(SimWorld w, Person p, app.radiance.content.Vocab.EntityKind kind) {
    return w.things.values().stream().anyMatch(t -> p.id.equals(t.ownerId) && t.kind.name().equals(kind.name()) && t.current());
  }

  static boolean faith(Person p, String tradition) {
    return p.faith != null && p.faith.toLowerCase().contains(tradition.toLowerCase());
  }
}
