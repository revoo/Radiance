package app.radiance.sim.model;

import app.radiance.content.ContentLibrary;
import app.radiance.content.Vocab.StatusKind;
import app.radiance.kernel.RandomSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The world the engine keeps in memory (SPEC §5.1): the committed world, or a planning copy of it.
 * Collections are ordered so every iteration — and so every plan built from it — is deterministic.
 */
public final class SimWorld {

  public final long seed;
  public final ZoneId zone;
  /** How far the world has aired (committed), or how far planning has reached (a copy). */
  public long time;
  public double tension;
  public long tensionAt;
  public Long lastSessionAt;
  public String userId;

  public final TreeMap<String, Person> people = new TreeMap<>();
  public final HashMap<String, Edge> edges = new HashMap<>();
  public final TreeMap<String, Arc> arcs = new TreeMap<>();
  public final TreeMap<String, Thing> things = new TreeMap<>();
  /** actor → tag → stored constraint */
  public final TreeMap<String, TreeMap<String, Tag>> tags = new TreeMap<>();
  /** {@code ARC:<id>:<actor>} and the like (SPEC §5.6) */
  public final TreeSet<String> knowledge = new TreeSet<>();
  /** {@code <actor>|<def>} → when it fired: cooldowns and caps read these */
  public final TreeMap<String, TreeSet<Long>> fired = new TreeMap<>();
  /** importance ≥ 6 events per actor, for {@code max-major-events-per-actor-per-14d} */
  public final TreeMap<String, TreeSet<Long>> majorEvents = new TreeMap<>();
  /** world date → DRAMA events that day */
  public final TreeMap<String, Integer> dramaByDay = new TreeMap<>();
  /** {@link Edge#pair} → prior clashes between the two, for META (SPEC §5.8) */
  public final TreeMap<String, Integer> clashes = new TreeMap<>();
  /** Recent posts, comments and DMs by id. */
  public final TreeMap<String, Item> items = new TreeMap<>();
  public final List<Intent> intents = new ArrayList<>();
  /** Tags removed since the last write, for the store to delete: {@code actor|tag}. */
  public final TreeSet<String> removedTags = new TreeSet<>();
  public final TreeSet<String> newKnowledge = new TreeSet<>();
  public final List<Thing> newThings = new ArrayList<>();

  public SimWorld(long seed, ZoneId zone, long time) {
    this.seed = seed;
    this.zone = zone;
    this.time = time;
    this.tensionAt = time;
  }

  /** A deep copy for planning: mutating it never touches this world. */
  public SimWorld copy() {
    var w = new SimWorld(seed, zone, time);
    w.tension = tension;
    w.tensionAt = tensionAt;
    w.lastSessionAt = lastSessionAt;
    w.userId = userId;
    people.forEach((k, v) -> w.people.put(k, v.copy()));
    edges.forEach((k, v) -> w.edges.put(k, v.copy()));
    arcs.forEach((k, v) -> w.arcs.put(k, v.copy()));
    things.forEach((k, v) -> w.things.put(k, v.copy()));
    tags.forEach((k, v) -> w.tags.put(k, new TreeMap<>(v)));
    w.knowledge.addAll(knowledge);
    fired.forEach((k, v) -> w.fired.put(k, new TreeSet<>(v)));
    majorEvents.forEach((k, v) -> w.majorEvents.put(k, new TreeSet<>(v)));
    w.dramaByDay.putAll(dramaByDay);
    w.clashes.putAll(clashes);
    items.forEach((k, v) -> w.items.put(k, v.copy()));
    w.intents.addAll(intents);
    return w;
  }

  public LocalDate date(long t) {
    return Instant.ofEpochMilli(t).atZone(zone).toLocalDate();
  }

  public String day(long t) {
    return date(t).toString();
  }

  public List<Person> residents() {
    return people.values().stream().filter(p -> !p.user).toList();
  }

  public Person person(String id) {
    var p = people.get(id);
    if (p == null) throw new IllegalArgumentException("no actor " + id);
    return p;
  }

  public Edge edgeOrNull(String from, String to) {
    return edges.get(Edge.key(from, to));
  }

  /** How {@code from} sees {@code to}; a stranger edge is created on first use. */
  public Edge edge(String from, String to) {
    return edges.computeIfAbsent(Edge.key(from, to), k -> {
      var e = Edge.stranger(from, to, chemistry(from, to), time);
      e.dirty = true;
      return e;
    });
  }

  /** Chemistry for a pair that genesis didn't seed: fixed per pair, −0.3…0.3. */
  public double chemistry(String a, String b) {
    var other = edges.get(Edge.key(b, a));
    if (other != null) return other.chemistry;
    return Math.round(RandomSource.seeded(seed).derive("chemistry:" + Edge.pair(a, b)).nextDouble(-0.3, 0.3) * 100)
        / 100.0;
  }

  /** Everyone {@code id} has a row toward. */
  public List<Edge> edgesFrom(String id) {
    var out = new ArrayList<Edge>();
    for (var e : edges.values()) if (e.from.equals(id)) out.add(e);
    out.sort(java.util.Comparator.comparing(e -> e.to));
    return out;
  }

  public List<Arc> arcsOf(String actorId) {
    return arcs.values().stream().filter(a -> a.active() && a.involves(actorId)).toList();
  }

  /**
   * Every constraint tag on {@code actorId} at {@code t} (SPEC §5.5): stored ones, the current phase's
   * tags of every active arc they're in ({@code {role}} and {@code {partner}} resolved to actor ids),
   * and {@code single} / {@code in_relationship:<partner>} from their status.
   */
  public TreeSet<String> activeTags(String actorId, long t, ContentLibrary lib) {
    var out = new TreeSet<String>();
    var stored = tags.get(actorId);
    if (stored != null) stored.values().forEach(tag -> {
      if (tag.activeAt(t)) out.add(tag.tag());
    });
    for (var a : arcsOf(actorId)) {
      var def = lib.arcs().get(a.defId);
      if (def == null) continue;
      def.phases().stream().filter(p -> p.id().equals(a.phase)).findFirst().ifPresent(phase -> {
        for (String c : phase.constraints()) out.add(resolve(c, a, actorId));
      });
    }
    var p = people.get(actorId);
    if (p != null && !p.user) {
      if (p.status == StatusKind.SINGLE || p.status == StatusKind.DIVORCED || p.status == StatusKind.WIDOWED) {
        out.add("single");
      }
      if (p.partnerId != null) out.add("in_relationship:" + p.partnerId);
    }
    return out;
  }

  private static String resolve(String tag, Arc a, String actorId) {
    String r = tag;
    if (r.contains("{partner}")) {
      String partner = a.roles.values().stream().filter(v -> !v.equals(actorId)).findFirst().orElse(actorId);
      r = r.replace("{partner}", partner);
    }
    for (var e : a.roles.entrySet()) r = r.replace("{" + e.getKey() + "}", e.getValue());
    for (var e : a.data.entrySet()) r = r.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
    return r;
  }

  public boolean knows(String subjectType, String subjectId, String actorId) {
    return knowledge.contains(subjectType + ":" + subjectId + ":" + actorId);
  }

  public void learn(String subjectType, String subjectId, String actorId) {
    String k = subjectType + ":" + subjectId + ":" + actorId;
    if (knowledge.add(k)) newKnowledge.add(k);
  }

  /** Tension decays exponentially toward 0 (half-life ≈ 2 days) between events. */
  public void settleTension(long now, double halfLifeDays) {
    if (now <= tensionAt) return;
    tension *= Math.pow(0.5, (now - tensionAt) / (halfLifeDays * 86_400_000.0));
    tensionAt = now;
  }

  /** The highest-familiarity friend of {@code id}, for {@code closest_friend_of:<role>} knowers. */
  public String closestFriend(String id) {
    String best = null;
    double score = Double.NEGATIVE_INFINITY;
    for (var e : edgesFrom(id)) {
      var other = people.get(e.to);
      if (other == null || other.user) continue;
      double s = e.familiarity + e.affinity + (e.hasTag("best_friend") ? 1000 : 0);
      if (s > score) {
        score = s;
        best = e.to;
      }
    }
    return best;
  }

  public Map<String, Person> byHandle() {
    var m = new HashMap<String, Person>();
    people.values().forEach(p -> m.put(p.handle, p));
    return m;
  }
}
