package app.radiance.sim;

import app.radiance.content.ContentLibrary;
import app.radiance.content.Rules.Deltas;
import app.radiance.content.Vocab.Category;
import app.radiance.content.Vocab.StatusKind;
import app.radiance.graph.RelationshipRow.Kind;
import app.radiance.kernel.AirStatus;
import app.radiance.kernel.Json;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Ulid;
import app.radiance.kernel.Visibility;
import app.radiance.memory.MemoryRow;
import app.radiance.sim.model.Arc;
import app.radiance.sim.model.Drift;
import app.radiance.sim.model.Edge;
import app.radiance.sim.model.Item;
import app.radiance.sim.model.Meters;
import app.radiance.sim.model.Person;
import app.radiance.sim.model.SimWorld;
import app.radiance.sim.model.Tag;
import app.radiance.sim.model.Thing;
import app.radiance.social.NotificationRow;
import app.radiance.social.ReactionRow;
import app.radiance.story.ArcRow;
import app.radiance.story.EntityRow;
import app.radiance.weather.WeatherBook;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Airing (SPEC §5.1): each scripted item, in time order, is validated against the world, then applied
 * — relationship deltas through temperament and mood, memories, knowledge, notifications, arc changes,
 * constraints, entities — or dropped with everything that depends on it. No LLM calls.
 *
 * <p>Planning runs the same code on its copy of the world with validation off, so a script's effects
 * are exactly what airing would apply if nothing changed in between (one effects path).
 */
public final class Airing {

  private Airing() {}

  /** Status of an item this run hasn't seen and the world no longer keeps in memory: the DB knows. */
  public interface Lookup {
    AirStatus status(Script.Type type, String id);

    Lookup NONE = (type, id) -> null;
  }

  public record Ctx(ContentLibrary lib, Knobs knobs, WeatherBook weather, boolean validate, Lookup lookup) {}

  /** What a run produced besides the changes to the world itself. */
  public static final class Out {
    /** {@code TYPE:id} → AIRED or DROPPED, in airing order. */
    public final LinkedHashMap<String, AirStatus> statuses = new LinkedHashMap<>();
    public final List<MemoryRow> memories = new ArrayList<>();
    public final List<NotificationRow> notifications = new ArrayList<>();
    /** Posts whose reaction counts changed. */
    public final Set<String> touchedPosts = new LinkedHashSet<>();
    public final List<String> dropReasons = new ArrayList<>();
    public int aired;
    public int dropped;
  }

  static final Set<String> CLASH = Set.of("DISAGREE", "TROLL", "MOCK", "HOSTILE");
  static final Set<String> UGLY = Set.of("TROLL", "MOCK", "HOSTILE");
  private static final Pattern DAYS = Pattern.compile("(\\d+)d");
  private static final long DAY = 86_400_000L;

  public static Out air(SimWorld w, List<Script.Entry> entries, Ctx ctx) {
    var out = new Out();
    for (var e : entries) {
      settle(w, e.time(), ctx);
      String why = ctx.validate() ? check(w, e, out, ctx) : null;
      if (why != null) {
        out.statuses.put(e.type() + ":" + e.id(), AirStatus.DROPPED);
        out.dropped++;
        out.dropReasons.add(e.type() + " " + e.id() + ": " + why);
        continue;
      }
      switch (e.type()) {
        case EVENT -> event(w, e, out, ctx);
        case POST -> post(w, e, out, ctx);
        case COMMENT -> comment(w, e, out, ctx);
        case REACTION -> reaction(w, e, out, ctx);
        case DM -> dm(w, e, out, ctx);
      }
      out.statuses.put(e.type() + ":" + e.id(), AirStatus.AIRED);
      out.aired++;
    }
    return out;
  }

  private static void settle(SimWorld w, long t, Ctx ctx) {
    w.settleTension(t, ctx.knobs().tensionHalfLifeDays());
  }

  // ------------------------------------------------------------------------------------- validation

  /** Why {@code e} can't air, or null. */
  static String check(SimWorld w, Script.Entry e, Out out, Ctx ctx) {
    var lib = ctx.lib();
    switch (e.type()) {
      case EVENT -> {
        var ev = e.event();
        var p = EventPayload.parse(ev.payloadJson());
        if (p.weather() != null && (ctx.weather() == null || !ctx.weather().holds(p.weather(), w.date(ev.worldTime())))) {
          return "weather '" + p.weather() + "' no longer holds";
        }
        if (p.arc() != null) return arcCheck(w, p.arc(), lib);
        var def = ev.defId() == null ? null : lib.events().get(ev.defId());
        if (def != null && ev.actorId() != null && w.people.containsKey(ev.actorId())
            && !Eligibility.holds(def, w.person(ev.actorId()), w, lib, ctx.weather(), ev.worldTime())) {
          return "def " + def.id() + " no longer holds for its actor";
        }
        for (String actor : p.participants().values()) if (!w.people.containsKey(actor)) return "unknown participant";
        return null;
      }
      case POST -> {
        var post = e.post();
        if (post.eventId() != null && status(w, Script.Type.EVENT, post.eventId(), out, ctx) == AirStatus.DROPPED) {
          return "its event was dropped";
        }
        if (post.arcId() != null && post.eventId() == null) {
          var arc = w.arcs.get(post.arcId());
          if (arc == null || !arc.active()) return "its arc is no longer active";
        }
        var b = Brief.parse(post.briefJson());
        var def = b.defId() == null ? null : lib.events().get(b.defId());
        if (def != null && post.eventId() == null
            && !Eligibility.holds(def, w.person(post.authorId()), w, lib, ctx.weather(), post.createdAt())) {
          return "def " + def.id() + " no longer holds for its author";
        }
        return null;
      }
      case COMMENT -> {
        var c = e.comment();
        if (status(w, Script.Type.POST, c.postId(), out, ctx) != AirStatus.AIRED) return "its post didn't air";
        if (c.parentId() != null && status(w, Script.Type.COMMENT, c.parentId(), out, ctx) != AirStatus.AIRED) {
          return "the comment it replies to didn't air";
        }
        var b = Brief.parse(c.briefJson());
        boolean answeringR = w.userId != null && w.userId.equals(b.toward());
        return answeringR ? null : attention(w, c.authorId(), c.createdAt());
      }
      case REACTION -> {
        var r = e.reaction();
        var type = r.targetType() == ReactionRow.Target.POST ? Script.Type.POST : Script.Type.COMMENT;
        if (status(w, type, r.targetId(), out, ctx) != AirStatus.AIRED) return "its target didn't air";
        return attention(w, r.actorId(), r.createdAt());
      }
      case DM -> {
        return null;
      }
    }
    return null;
  }

  private static String arcCheck(SimWorld w, EventPayload.ArcChange c, ContentLibrary lib) {
    var arc = w.arcs.get(c.arcId());
    switch (c.kind()) {
      case "START" -> {
        if (arc != null) return "the arc already exists";
        if (lib.isBlacklisted(c.defId())) return "arc " + c.defId() + " is blacklisted";
        var def = lib.arcs().get(c.defId());
        for (String actor : c.roles().values()) {
          for (var other : w.arcsOf(actor)) {
            if (def != null && (def.exclusiveWith().contains(other.defId) || other.defId.equals(c.defId()))) {
              return "@" + w.person(actor).handle + " is already in a " + other.defId + " arc";
            }
          }
        }
        return null;
      }
      case "TRANSITION" -> {
        if (arc == null || !arc.active()) return "the arc isn't active";
        if (!arc.phase.equals(c.fromPhase())) return "the arc is in " + arc.phase + ", not " + c.fromPhase();
        return null;
      }
      default -> {
        return arc == null || !arc.active() ? "the arc isn't active" : null;
      }
    }
  }

  private static String attention(SimWorld w, String actorId, long t) {
    var p = w.people.get(actorId);
    if (p == null) return "unknown author";
    if (p.user) return null;
    return p.interactionsOn(w.day(t)) >= p.routine.attentionBudget() ? "@" + p.handle + " is out of attention today" : null;
  }

  private static AirStatus status(SimWorld w, Script.Type type, String id, Out out, Ctx ctx) {
    var s = out.statuses.get(type + ":" + id);
    if (s != null) return s;
    var item = w.items.get(id);
    if (item != null) return item.status;
    var looked = ctx.lookup().status(type, id);
    if (looked != null) return looked;
    return ctx.validate() ? AirStatus.DROPPED : AirStatus.AIRED;
  }

  // ------------------------------------------------------------------------------------- events

  static void event(SimWorld w, Script.Entry e, Out out, Ctx ctx) {
    var ev = e.event();
    var p = EventPayload.parse(ev.payloadJson());
    long t = ev.worldTime();
    String actor = ev.actorId();
    for (String id : p.participants().values()) Drift.advance(w.person(id), w.seed, ctx.knobs().moodHalfLifeHours(), t);
    if (actor != null && ev.defId() != null) w.fired.computeIfAbsent(actor + "|" + ev.defId(), k -> new java.util.TreeSet<>()).add(t);
    if (actor != null && ev.importance() >= 6) w.majorEvents.computeIfAbsent(actor, k -> new java.util.TreeSet<>()).add(t);
    boolean drama = Category.DRAMA.name().equals(p.category());
    // the storyteller's daily DRAMA cap counts its own incidents; arcs run on their own clocks
    if (drama && p.arc() == null) w.dramaByDay.merge(w.day(t), 1, Integer::sum);

    Object tension = p.effects().get("tension");
    if (tension instanceof Number n) {
      w.tension = Math.min(100, w.tension + n.doubleValue());
    } else if (drama || Category.LIFE.name().equals(p.category())) {
      w.tension = Math.min(100, w.tension + (drama ? ev.importance() : ev.importance() / 2.0));
    }

    if (p.arc() != null) arc(w, p.arc(), ev.id(), t, ctx);
    if (p.entity() != null) {
      var n = p.entity();
      var thing = new Thing(n.id(), n.ownerId(), EntityRow.Kind.valueOf(n.kind()), n.name(), n.visualDescription(),
          n.imageSeed(), n.attributes(), "OWNED", t, t);
      thing.dirty = true;
      w.things.put(thing.id, thing);
    }
    effects(w, p, ev.id(), t, ctx);
    for (var k : p.learned()) w.learn(k.get(0), k.get(1), k.get(2));

    if (ev.importance() >= 3) {
      String text = p.text() != null ? p.text() : ev.type().toLowerCase().replace('_', ' ');
      var involved = new ArrayList<>(new LinkedHashSet<>(p.participants().values()));
      for (String id : involved) {
        boolean subject = id.equals(actor) || (actor == null && id.equals(involved.getFirst()));
        int imp = subject ? clamp(ev.importance(), 6, 9) : clamp(ev.importance() - 2, 3, 6);
        var names = involved.stream().filter(x -> !x.equals(id)).toList();
        out.memories.add(memory(w, id, t, w.person(id).name + ": " + text, imp, names, List.of(ev.type().toLowerCase()),
            ev.arcId(), ev.id()));
      }
      if (ev.visibility() == Visibility.PUBLIC && ev.importance() >= 6 && actor != null) {
        int n = 0;
        for (var edge : w.edgesFrom(actor)) {
          var friend = w.people.get(edge.to);
          if (friend == null || friend.user || involved.contains(edge.to) || !edge.connected) continue;
          var back = w.edgeOrNull(edge.to, actor);
          if (back == null || back.familiarity < 40) continue;
          out.memories.add(memory(w, edge.to, t, w.person(actor).name + ": " + text, clamp(ev.importance() - 5, 1, 3),
              List.of(actor), List.of("witness"), ev.arcId(), ev.id()));
          if (++n >= 5) break;
        }
      }
    }
  }

  static void arc(SimWorld w, EventPayload.ArcChange c, String eventId, long t, Ctx ctx) {
    var arc = w.arcs.get(c.arcId());
    if ("START".equals(c.kind())) {
      arc = new Arc(c.arcId(), c.defId(), ArcRow.Status.ACTIVE, c.toPhase(), Visibility.valueOf(c.visibility()), t, t,
          c.nextTransitionAt(), t, c.roles(), c.data(), c.summary(), null, null);
      arc.isNew = true;
      w.arcs.put(arc.id, arc);
    } else if (arc != null) {
      if ("TRANSITION".equals(c.kind())) {
        arc.phase = c.toPhase();
        arc.phaseEnteredAt = t;
        arc.visibility = Visibility.valueOf(c.visibility());
        arc.nextTransitionAt = c.nextTransitionAt();
        arc.data.putAll(c.data());
        if (c.summary() != null) arc.summary = c.summary();
      }
      arc.lastBeatAt = t;
      if (!"ACTIVE".equals(c.status())) {
        arc.status = ArcRow.Status.valueOf(c.status());
        arc.endedAt = t;
        arc.endEventId = eventId;
        arc.nextTransitionAt = null;
      }
      arc.dirty = true;
    }
    if (arc != null) for (String k : c.knowers()) w.learn("ARC", arc.id, k);
  }

  /** The def's effects block, with role names resolved through the event's participants. */
  @SuppressWarnings("unchecked")
  static void effects(SimWorld w, EventPayload p, String eventId, long t, Ctx ctx) {
    var roles = p.participants();
    boolean arcEvent = p.arc() != null;
    List<String> subjects = arcEvent ? List.copyOf(new LinkedHashSet<>(roles.values()))
        : roles.containsKey("actor") ? List.of(roles.get("actor")) : List.copyOf(roles.values());
    for (var e : p.effects().entrySet()) {
      String key = e.getKey();
      Object v = e.getValue();
      switch (key) {
        case "mood", "energy", "stress", "partner_satisfaction" -> {
          for (String id : subjects) shiftState(w.person(id), key, ((Number) v).doubleValue());
        }
        case "add_constraints" -> {
          for (String id : subjects) for (Object o : (List<Object>) v) addTag(w, id, String.valueOf(o), t, eventId);
        }
        case "remove_constraints" -> {
          for (String id : subjects) for (Object o : (List<Object>) v) removeTag(w, id, String.valueOf(o));
        }
        case "add_relationship_tags", "remove_relationship_tags" -> {
          for (Object o : (List<Object>) v) relTag(w, roles, String.valueOf(o), key.startsWith("add"), t);
        }
        case "relationship_status" -> {
          var status = StatusKind.valueOf(String.valueOf(v));
          for (String id : subjects) {
            var person = w.person(id);
            if (person.user) continue;
            person.status = status;
            if (status == StatusKind.SINGLE) person.partnerId = null;
            person.dirtyActor = true;
          }
        }
        case "relationship_kind" -> {
          var kind = Kind.valueOf(String.valueOf(v));
          for (String a : subjects) {
            for (String b : subjects) {
              if (a.equals(b)) continue;
              var edge = w.edge(a, b);
              edge.kind = kind;
              edge.dirty = true;
              var pa = w.person(a);
              if (kind == Kind.PARTNER) pa.partnerId = b;
              if (kind == Kind.EX && b.equals(pa.partnerId)) pa.partnerId = null;
              pa.dirtyActor = true;
            }
          }
        }
        case "end_relationship" -> {
          for (String id : subjects) {
            var person = w.person(id);
            String partner = person.partnerId;
            if (partner == null) continue;
            for (var pair : List.of(List.of(id, partner), List.of(partner, id))) {
              var edge = w.edge(pair.get(0), pair.get(1));
              edge.kind = Kind.EX;
              edge.dirty = true;
              var x = w.person(pair.get(0));
              x.partnerId = null;
              x.status = StatusKind.SINGLE;
              x.dirtyActor = true;
            }
          }
        }
        case "entity_status" -> {
          Object entity = p.arc() != null ? p.arc().data().get("entity") : null;
          if (entity == null && p.arc() != null) entity = w.arcs.get(p.arc().arcId()) == null ? null
              : w.arcs.get(p.arc().arcId()).data.get("entity");
          var thing = entity == null ? null : w.things.get(String.valueOf(entity));
          if (thing != null) {
            thing.status = String.valueOf(v);
            thing.updatedAt = t;
            thing.dirty = true;
          }
        }
        case "trait_nudges" -> {
          for (String id : subjects) {
            var person = w.person(id);
            ((Map<String, Object>) v).forEach((trait, n) -> person.traitOffsets.merge(trait, ((Number) n).doubleValue(),
                (a, b) -> Meters.clamp(a + b, -ctx.knobs().maxTraitDrift(), ctx.knobs().maxTraitDrift())));
            person.dirtyState = true;
          }
        }
        case "tension", "start_arc", "creates_entity", "knowledge_spread" -> {
          // tension is applied with the event; arcs start, entities appear and knowledge spreads through
          // their own payload fields, which the planner fills in
        }
        default -> {
          if (key.contains("->")) pairDeltas(w, roles, key, (Map<String, Object>) v, arcEvent, t, ctx);
        }
      }
    }
  }

  private static void shiftState(Person p, String key, double d) {
    if (p.user) return;
    switch (key) {
      case "mood" -> p.mood = Meters.clamp(p.mood + d, -1, 1);
      case "energy" -> p.energy = Meters.clamp(p.energy + d, 0, 1);
      case "stress" -> p.stress = Meters.clamp(p.stress + d, 0, 1);
      default -> {
        if (p.partnerSatisfaction != null) p.partnerSatisfaction = Meters.clamp(p.partnerSatisfaction + d, 0, 1);
      }
    }
    p.dirtyState = true;
  }

  /** {@code budget_tight:30d} lasts 30 days; {@code injured:knee} until an event removes it. */
  static void addTag(SimWorld w, String actor, String spec, long t, String eventId) {
    String tag = spec;
    Long until = null;
    int i = spec.lastIndexOf(':');
    if (i > 0) {
      var m = DAYS.matcher(spec.substring(i + 1));
      if (m.matches()) {
        tag = spec.substring(0, i);
        until = t + Long.parseLong(m.group(1)) * DAY;
      }
    }
    w.tags.computeIfAbsent(actor, k -> new TreeMap<>()).put(tag, new Tag(tag, t, until, eventId));
    w.removedTags.remove(actor + "|" + tag);
  }

  static void removeTag(SimWorld w, String actor, String spec) {
    var m = w.tags.get(actor);
    if (m == null) return;
    for (String tag : List.copyOf(m.keySet())) {
      if (tag.equals(spec) || Tag.base(tag).equals(Tag.base(spec))) {
        m.remove(tag);
        w.removedTags.add(actor + "|" + tag);
      }
    }
  }

  /** {@code "a <-> b: avoiding"}, {@code "friends -> owner: teases_about:cars"}. */
  static void relTag(SimWorld w, Map<String, String> roles, String spec, boolean add, long t) {
    int colon = spec.indexOf(": ");
    String who = spec.substring(0, colon).strip();
    String tag = spec.substring(colon + 2).strip();
    boolean both = who.contains("<->");
    var sides = who.split(both ? "<->" : "->");
    for (String from : resolve(w, roles, sides[0].strip(), roles.get(sides[1].strip()))) {
      for (String to : resolve(w, roles, sides[1].strip(), null)) {
        if (from.equals(to)) continue;
        tagEdge(w.edge(from, to), tag, add);
        if (both) tagEdge(w.edge(to, from), tag, add);
      }
    }
  }

  private static void tagEdge(Edge e, String tag, boolean add) {
    if (add ? e.tags.add(tag) : e.tags.remove(tag)) e.dirty = true;
  }

  /** A role's actor, or {@code friends}: the connected friends of {@code of}. */
  private static List<String> resolve(SimWorld w, Map<String, String> roles, String name, String of) {
    if (name.equals("friends") && of != null) {
      var out = new ArrayList<String>();
      for (var e : w.edgesFrom(of)) {
        var other = w.people.get(e.to);
        if (other != null && !other.user && e.connected && e.affinity > 15) out.add(e.to);
      }
      return out;
    }
    String id = roles.get(name);
    return id == null ? List.of() : List.of(id);
  }

  private static void pairDeltas(SimWorld w, Map<String, String> roles, String key, Map<String, Object> deltas,
      boolean arcEvent, long t, Ctx ctx) {
    boolean both = key.contains("<->");
    var sides = key.split(both ? "<->" : "->");
    String a = roles.get(sides[0].strip());
    String b = roles.get(sides[1].strip());
    if (a == null || b == null || a.equals(b)) return;
    var d = new Deltas(num(deltas, "affinity"), num(deltas, "trust"), num(deltas, "attraction"),
        num(deltas, "familiarity"), num(deltas, "respect"));
    move(w, a, b, d, arcEvent, t, ctx);
    if (both) move(w, b, a, d, arcEvent, t, ctx);
  }

  private static double num(Map<String, Object> m, String k) {
    return m.get(k) instanceof Number n ? n.doubleValue() : 0;
  }

  /** {@code viewer}'s edge toward {@code other} moves by {@code d}, settled to {@code t} first. */
  static void move(SimWorld w, String viewer, String other, Deltas d, boolean arcEvent, long t, Ctx ctx) {
    var v = w.person(viewer);
    var e = w.edge(viewer, other);
    Meters.settle(e, v, ctx.lib().relationships(), ctx.knobs().affinityHalfLifeDays(), t);
    Drift.advance(v, w.seed, ctx.knobs().moodHalfLifeHours(), t);
    Meters.apply(e, d, v, ctx.lib().temperament(), arcEvent, t);
    if (!v.user) Meters.reconsiderKind(e, v, ctx.lib().relationships());
  }

  // ------------------------------------------------------------------------------------- social items

  static void post(SimWorld w, Script.Entry e, Out out, Ctx ctx) {
    var p = e.post();
    var b = Brief.parse(p.briefJson());
    var item = new Item(p.id(), Item.Type.POST, p.authorId(), null, null, null, p.createdAt(), AirStatus.AIRED,
        p.kind().name(), b.category(), b.defId(), b.stance(), b.topics(), b.controversy(), b.drama(), p.importance(),
        p.arcId(), p.eventId(), p.headlineId(), p.audience() == app.radiance.social.PostRow.Audience.PUBLIC);
    Briefs.decorate(item, b);
    w.items.put(item.id, item);
    if (p.headlineId() != null) w.fired.computeIfAbsent(p.authorId() + "|news:" + p.headlineId(), k -> new java.util.TreeSet<>())
        .add(p.createdAt());
    if (p.arcId() != null) {
      var arc = w.arcs.get(p.arcId());
      if (arc != null && arc.active()) {
        arc.lastBeatAt = p.createdAt();
        arc.dirty = true;
      }
    }
    if (p.importance() >= 4 && p.eventId() == null) {
      out.memories.add(memory(w, p.authorId(), p.createdAt(), w.person(p.authorId()).name + " posted: "
          + (b.text() == null ? "a " + p.kind().name().toLowerCase() + " post" : b.text()), clamp(p.importance(), 3, 6),
          List.of(), List.of("post"), p.arcId(), p.eventId()));
    }
    Object mentions = b.extra().get("mentions");
    if (w.userId != null && mentions instanceof List<?> l && l.contains(w.userId)) {
      out.notifications.add(note(w, "MENTION", p.authorId(), "POST", p.id(), p.createdAt()));
    }
  }

  static void comment(SimWorld w, Script.Entry e, Out out, Ctx ctx) {
    var c = e.comment();
    var b = Brief.parse(c.briefJson());
    var post = w.items.get(c.postId());
    String receiver = b.toward() != null ? b.toward() : post != null ? post.authorId : null;
    var item = new Item(c.id(), Item.Type.COMMENT, c.authorId(), c.postId(), c.parentId(), receiver, c.createdAt(),
        AirStatus.AIRED, c.parentId() == null ? "COMMENT" : "REPLY", b.category(), b.defId(), b.stance(), b.topics(),
        b.controversy(), b.drama(), 1, null, null, null, false);
    w.items.put(item.id, item);
    var author = w.person(c.authorId());
    author.countInteraction(w.day(c.createdAt()));
    if (receiver == null || receiver.equals(c.authorId())) {
      if (post != null) {
        post.commentCount++;
        post.commenters.add(c.authorId());
      }
      return;
    }
    String key = b.interaction() != null ? b.interaction() : interactionFor(b.stance());
    var rule = ctx.lib().relationships().interactions().get(key);
    long t = c.createdAt();
    if (rule != null) {
      var target = rule.target();
      if ("DISAGREE".equals(b.stance())) {
        double civility = author.trait("agreeableness") >= 50 ? 1 : -1;
        target = new Deltas(target.affinity(), target.trust(), target.attraction(), target.familiarity(),
            target.respect() + civility);
      }
      move(w, receiver, c.authorId(), target, false, t, ctx);
      move(w, c.authorId(), receiver, rule.actor(), false, t, ctx);
      if (post != null && !rule.bystanders().equals(Deltas.ZERO)) {
        var bystanders = new LinkedHashSet<String>();
        bystanders.add(post.authorId);
        bystanders.addAll(post.commenters);
        bystanders.remove(c.authorId());
        bystanders.remove(receiver);
        int n = 0;
        for (String id : bystanders) {
          if (w.people.containsKey(id)) move(w, id, c.authorId(), rule.bystanders(), false, t, ctx);
          if (++n >= 5) break;
        }
      }
    }
    if (post != null) {
      post.commentCount++;
      post.commenters.add(c.authorId());
    }
    if (CLASH.contains(b.stance()) || "debate_exchange".equals(key)) {
      w.clashes.merge(Edge.pair(c.authorId(), receiver), 1, Integer::sum);
      // the renderer's gist, when the comment was written before it aired (SPEC §5.7)
      String text = c.gist() != null ? c.gist() : author.name + " and " + w.person(receiver).name + " clashed in the comments"
          + (b.topics().isEmpty() ? "" : " about " + b.topics().getFirst());
      int imp = UGLY.contains(b.stance()) ? 5 : 3;
      out.memories.add(memory(w, c.authorId(), t, text, imp, List.of(receiver), List.of("clash"), null, c.id()));
      out.memories.add(memory(w, receiver, t, text, imp + 1, List.of(c.authorId()), List.of("clash"), null, c.id()));
    } else if ("FLIRT".equals(b.stance())) {
      out.memories.add(memory(w, receiver, t, c.gist() != null ? c.gist() : author.name + " flirted in the comments", 4,
          List.of(c.authorId()),
          List.of("flirt"), null, c.id()));
      jealousy(w, receiver, c.authorId(), t, ctx);
    }
    if (w.userId != null && !c.authorId().equals(w.userId)) {
      if (c.parentId() != null && w.userId.equals(receiver)) {
        out.notifications.add(note(w, "REPLY", c.authorId(), "COMMENT", c.id(), t));
      } else if (post != null && w.userId.equals(post.authorId)) {
        out.notifications.add(note(w, "COMMENT", c.authorId(), "COMMENT", c.id(), t));
      }
    }
  }

  /** An in-network partner who sees flirting with their partner takes it badly (SPEC §5.3). */
  private static void jealousy(SimWorld w, String flirtedWith, String flirt, long t, Ctx ctx) {
    String partner = w.person(flirtedWith).partnerId;
    if (partner == null || partner.equals(flirt) || !w.people.containsKey(partner)) return;
    move(w, partner, flirtedWith, new Deltas(-3, -5, 0, 0, 0), false, t, ctx);
    move(w, partner, flirt, new Deltas(-6, -4, 0, 1, -2), false, t, ctx);
  }

  static void reaction(SimWorld w, Script.Entry e, Out out, Ctx ctx) {
    var r = e.reaction();
    var target = w.items.get(r.targetId());
    var actor = w.person(r.actorId());
    actor.countInteraction(w.day(r.createdAt()));
    if (target == null) return;
    target.reactors.add(r.actorId());
    target.reactionCounts.merge(r.kind().name(), 1, Integer::sum);
    if (target.type == Item.Type.POST) out.touchedPosts.add(target.id);
    if (target.authorId.equals(r.actorId())) return;
    var rule = ctx.lib().relationships().interactions().get(switch (r.kind()) {
      case LOVE -> "love_reaction";
      case CARE, SAD -> "care_reaction";
      case HAHA -> "haha_reaction";
      case ANGRY -> "angry_reaction";
      default -> "like";
    });
    if (rule != null) move(w, target.authorId, r.actorId(), rule.target(), false, r.createdAt(), ctx);
    if (w.userId != null && w.userId.equals(target.authorId)) {
      out.notifications.add(note(w, "REACTION", r.actorId(), r.targetType().name(), r.targetId(), r.createdAt()));
    }
  }

  static void dm(SimWorld w, Script.Entry e, Out out, Ctx ctx) {
    var m = e.dm();
    var b = Brief.parse(m.briefJson());
    String to = b.toward();
    var item = new Item(m.id(), Item.Type.DM, m.senderId(), null, null, to, m.createdAt(), AirStatus.AIRED, "DM",
        b.category(), b.defId(), b.stance(), b.topics(), 0, 0, 2, null, null, null, false);
    w.items.put(item.id, item);
    if (to == null) return;
    var rule = ctx.lib().relationships().interactions().get(b.interaction() != null ? b.interaction() : "dm_warm");
    if (rule != null) {
      move(w, to, m.senderId(), rule.target(), false, m.createdAt(), ctx);
      move(w, m.senderId(), to, rule.actor(), false, m.createdAt(), ctx);
    }
    if (to.equals(w.userId)) out.notifications.add(note(w, "DM", m.senderId(), "DM", m.id(), m.createdAt()));
  }

  /** The rule-table key for a comment stance. */
  public static String interactionFor(String stance) {
    if (stance == null) return "supportive_comment";
    return switch (stance) {
      case "CONGRATS" -> "congrats_comment";
      case "CONDOLENCE" -> "condolence_comment";
      case "JOKE" -> "joke_comment";
      case "AGREE" -> "agree_comment";
      case "DISAGREE" -> "disagree_comment";
      case "TROLL" -> "troll_comment";
      case "MOCK" -> "mock_comment";
      case "HOSTILE" -> "hostile_comment";
      case "FLIRT" -> "flirt_comment";
      case "QUESTION" -> "question_comment";
      case "ADVICE" -> "advice_comment";
      case "SELF_PIVOT" -> "self_pivot_comment";
      case "HYPE" -> "hype_comment";
      case "META" -> "meta_comment";
      default -> "supportive_comment";
    };
  }

  // ------------------------------------------------------------------------------------- rows

  static MemoryRow memory(SimWorld w, String owner, long t, String text, int importance, List<String> participants,
      List<String> tags, String arcId, String eventId) {
    String id = Ulid.next(t, RandomSource.seeded(w.seed).derive("memory:" + owner + ":" + eventId + ":" + text.hashCode()));
    return new MemoryRow(id, owner, t, MemoryRow.Kind.EPISODE, text, importance, Json.write(participants),
        Json.write(tags), arcId, eventId, false);
  }

  static NotificationRow note(SimWorld w, String kind, String actor, String refType, String refId, long t) {
    String id = Ulid.next(t, RandomSource.seeded(w.seed).derive("note:" + kind + ":" + actor + ":" + refId));
    return new NotificationRow(id, kind, actor, refType, refId, t, null);
  }

  static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
