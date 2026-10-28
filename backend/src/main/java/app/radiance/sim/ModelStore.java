package app.radiance.sim;

import app.radiance.cast.ActorRow;
import app.radiance.cast.ActorStateRow;
import app.radiance.cast.CastRepository;
import app.radiance.cast.PersonaRow;
import app.radiance.content.ContentLibrary;
import app.radiance.content.Vocab;
import app.radiance.content.Vocab.Gender;
import app.radiance.content.Vocab.Observance;
import app.radiance.content.Vocab.StatusKind;
import app.radiance.content.Vocab.Temperament;
import app.radiance.graph.RelationshipRepository;
import app.radiance.graph.RelationshipRow;
import app.radiance.kernel.Json;
import app.radiance.memory.MemoryRepository;
import app.radiance.persistence.Schema;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.sim.model.Arc;
import app.radiance.sim.model.Edge;
import app.radiance.sim.model.Intent;
import app.radiance.sim.model.Item;
import app.radiance.sim.model.Person;
import app.radiance.sim.model.SimWorld;
import app.radiance.sim.model.Tag;
import app.radiance.sim.model.Thing;
import app.radiance.social.SocialRepository;
import app.radiance.story.ArcRepository;
import app.radiance.story.ArcRow;
import app.radiance.story.CanonRepository;
import app.radiance.story.ConstraintRepository;
import app.radiance.story.ConstraintRow;
import app.radiance.story.EntityRow;
import app.radiance.story.EventRepository;
import app.radiance.story.KnowledgeRow;
import app.radiance.world.WorldRepository;
import app.radiance.world.WorldRow;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * Loads the committed world into memory and writes back only what changed (SPEC §4.4: dirty rows
 * only). The imperative shell around the pure model in {@code sim.model}.
 */
public final class ModelStore {

  static final long DAY = 86_400_000L;
  /** How much recent history the engine keeps in memory: posts and comments, and the event log. */
  static final long ITEM_WINDOW = 3 * DAY;
  static final long EVENT_WINDOW = 60 * DAY;
  private static final Pattern KIDS = Pattern.compile("\\b(son|daughter|stepson|stepdaughter|kids?|twins?)\\b");

  private final Sql sql;
  private final ContentLibrary lib;

  public ModelStore(Sql sql, ContentLibrary lib) {
    this.sql = sql;
    this.lib = lib;
  }

  public SimWorld load() {
    WorldRow w = new WorldRepository(sql).load().orElseThrow(() -> new IllegalStateException("no world row"));
    var world = new SimWorld(w.seed(), ZoneId.of(w.timezone()), w.committedUntil());
    world.tension = w.tension();
    world.tensionAt = w.committedUntil();
    world.lastSessionAt = w.lastSessionAt();

    var cast = new CastRepository(sql);
    var personas = new HashMap<String, PersonaRow>();
    cast.personas().forEach(p -> personas.put(p.actorId(), p));
    var states = new HashMap<String, ActorStateRow>();
    cast.states().forEach(s -> states.put(s.actorId(), s));
    var actors = cast.actors();
    var idByHandle = new HashMap<String, String>();
    actors.forEach(a -> idByHandle.put(a.handle(), a.id()));
    for (var a : actors) {
      var p = person(a, personas.get(a.id()), idByHandle);
      var s = states.get(a.id());
      if (s != null) {
        p.mood = s.mood();
        p.energy = s.energy();
        p.stress = s.stress();
        p.partnerSatisfaction = s.partnerSatisfaction();
        p.traitOffsets = new HashMap<>(Json.MAPPER.readValue(s.traitOffsetsJson(), new TypeReference<Map<String, Double>>() {}));
        p.location = s.location();
        p.interactionsDay = s.interactionsDay();
        p.interactionsToday = (int) s.interactionsToday();
        p.lastFeedCheck = s.lastFeedCheckAt();
        p.stateAt = Math.min(s.updatedAt(), w.committedUntil());
      } else {
        p.mood = 0.1;
        p.energy = 0.6;
        p.stress = 0.25;
        p.traitOffsets = new HashMap<>();
        p.location = "HOME";
        p.stateAt = w.committedUntil();
      }
      if (a.kind() == ActorRow.Kind.USER) world.userId = a.id();
      world.people.put(p.id, p);
    }

    for (var r : new RelationshipRepository(sql).all()) {
      var tags = new TreeSet<String>(Json.MAPPER.readValue(r.tagsJson(), new TypeReference<List<String>>() {}));
      var pending = new TreeMap<String, Long>(Json.MAPPER.readValue(r.pendingTagsJson(),
          new TypeReference<Map<String, Long>>() {}));
      world.edges.put(Edge.key(r.fromId(), r.toId()), new Edge(r.fromId(), r.toId(), r.kind(), r.affinity(), r.trust(),
          r.attraction(), r.familiarity(), r.respect(), r.chemistry(), tags, pending, r.connected(), r.summary(),
          r.lastInteractionAt(), Math.min(r.updatedAt(), w.committedUntil())));
    }

    var arcRepo = new ArcRepository(sql);
    var roles = new HashMap<String, Map<String, String>>();
    arcRepo.participants().forEach(p -> roles.computeIfAbsent(p.arcId(), k -> new TreeMap<>()).put(p.role(), p.actorId()));
    for (var a : arcRepo.all()) {
      world.arcs.put(a.id(), new Arc(a.id(), a.defId(), a.status(), a.phase(), a.visibility(), a.startedAt(),
          a.phaseEnteredAt(), a.nextTransitionAt(), a.lastBeatAt(), roles.getOrDefault(a.id(), Map.of()),
          Json.MAPPER.readValue(a.dataJson(), new TypeReference<LinkedHashMap<String, Object>>() {}), a.summary(),
          a.endedAt(), a.endEventId()));
    }

    var canon = new CanonRepository(sql);
    for (var e : canon.entities()) {
      world.things.put(e.id(), new Thing(e.id(), e.ownerId(), e.kind(), e.name(), e.visualDescription(), e.imageSeed(),
          Json.MAPPER.readValue(e.attributesJson(), new TypeReference<LinkedHashMap<String, Object>>() {}), e.status(),
          e.createdAt(), e.updatedAt()));
    }
    for (var k : canon.knowledge()) world.knowledge.add(k.subjectType() + ":" + k.subjectId() + ":" + k.actorId());
    for (var c : new ConstraintRepository(sql).all()) {
      world.tags.computeIfAbsent(c.actorId(), k -> new TreeMap<>())
          .put(c.tag(), new Tag(c.tag(), c.since(), c.until(), c.sourceEventId()));
    }

    for (var e : new EventRepository(sql).airedBetween(w.committedUntil() - EVENT_WINDOW, w.committedUntil() + 1)) {
      if (e.actorId() == null) continue;
      if (e.defId() != null) world.fired.computeIfAbsent(e.actorId() + "|" + e.defId(), k -> new TreeSet<>()).add(e.worldTime());
      if (e.importance() >= 6) world.majorEvents.computeIfAbsent(e.actorId(), k -> new TreeSet<>()).add(e.worldTime());
      var def = e.defId() == null ? null : lib.events().get(e.defId());
      if (def != null && def.category() == Vocab.Category.DRAMA) world.dramaByDay.merge(world.day(e.worldTime()), 1, Integer::sum);
    }
    for (var m : new MemoryRepository(sql).tagged("clash")) {
      for (JsonNode p : Json.tree(m.participantsJson())) {
        if (m.ownerId().compareTo(p.asString()) < 0) world.clashes.merge(Edge.pair(m.ownerId(), p.asString()), 1, Integer::sum);
      }
    }

    var social = new SocialRepository(sql);
    for (var p : social.airedPostsSince(w.committedUntil() - ITEM_WINDOW)) {
      var b = Brief.parse(p.briefJson());
      var item = new Item(p.id(), Item.Type.POST, p.authorId(), null, null, null, p.createdAt(), p.airStatus(),
          p.kind().name(), b.category(), b.defId(), b.stance(), b.topics(), b.controversy(), b.drama(), p.importance(),
          p.arcId(), p.eventId(), p.headlineId(), p.audience() == app.radiance.social.PostRow.Audience.PUBLIC);
      item.commentCount = p.commentCount();
      world.items.put(p.id(), item);
    }
    for (var c : social.airedCommentsSince(w.committedUntil() - ITEM_WINDOW)) {
      var b = Brief.parse(c.briefJson());
      var item = new Item(c.id(), Item.Type.COMMENT, c.authorId(), c.postId(), c.parentId(), b.toward(), c.createdAt(),
          c.airStatus(), c.parentId() == null ? "COMMENT" : "REPLY", b.category(), b.defId(), b.stance(), b.topics(),
          b.controversy(), b.drama(), 1, null, null, null, false);
      world.items.put(c.id(), item);
      var post = world.items.get(c.postId());
      if (post != null) post.commenters.add(c.authorId());
    }
    for (var i : new ScheduledIntentRepository(sql).pending()) {
      world.intents.add(new Intent(i.id(), i.dueAt(), i.actorId(), i.kind(), i.targetType(), i.targetId(),
          Json.MAPPER.readValue(i.payloadJson(), new TypeReference<Map<String, Object>>() {}), i.causeEventId()));
    }
    return world;
  }

  private Person person(ActorRow a, PersonaRow p, Map<String, String> idByHandle) {
    boolean user = a.kind() == ActorRow.Kind.USER;
    var circles = Json.MAPPER.readValue(a.circlesJson(), new TypeReference<List<String>>() {});
    Gender gender = a.gender() == null ? Gender.M : Gender.valueOf(a.gender());
    if (user || p == null) {
      var person = new Person(a.id(), a.handle(), a.displayName(), user, gender, a.birthDate(), circles, Map.of(),
          java.util.Set.of(), Map.of(), new Person.Politics(0, 0, 0), List.of(), Map.of(), Person.Temper.NEUTRAL,
          new Person.Routine("office_9_to_5", flat(), flat(), 1, 40), null, Observance.NONE, false, false);
      person.status = a.relationshipStatus() == null ? StatusKind.SINGLE : StatusKind.valueOf(a.relationshipStatus());
      return person;
    }
    var traits = new HashMap<String, Double>();
    Json.tree(p.traitsJson()).properties().forEach(e -> traits.put(e.getKey(), e.getValue().asDouble()));
    var quirks = new HashSet<String>(Json.MAPPER.readValue(p.quirksJson(), new TypeReference<List<String>>() {}));
    var interests = Json.MAPPER.readValue(p.interestsJson(), new TypeReference<Map<String, Double>>() {});
    var pol = Json.tree(p.politicsJson());
    var politics = new Person.Politics(pol.path("economic").asDouble(0), pol.path("social").asDouble(0),
        pol.path("interest").asDouble(0));
    var biases = new ArrayList<Person.Bias>();
    for (var b : Json.tree(p.biasesJson())) {
      biases.add(new Person.Bias(b.path("target").asString(), b.path("strength").asDouble(0),
          b.path("style").asString(""), b.path("strong").asBoolean(false)));
    }
    var taste = Json.MAPPER.readValue(p.tasteJson(), new TypeReference<Map<String, Double>>() {});
    var t = Json.tree(p.temperamentJson());
    var temper = new Person.Temper(Temperament.valueOf(t.path("preset").asString("TYPICAL")), t.path("baseline").asDouble(10),
        t.path("goodGain").asDouble(1), t.path("badGain").asDouble(0.4), t.path("floor").asDouble(-30),
        t.path("ceiling").asDouble(100));
    var r = Json.tree(p.routineJson());
    var routine = new Person.Routine(r.path("schedule").asString("office_9_to_5"), curve(r.path("weekday")),
        curve(r.path("weekend")), r.path("postsPerDay").asDouble(0.5), r.path("attentionBudget").asInt(30));
    var id = Json.tree(p.identityJson());
    boolean kids = false;
    for (var o : Json.tree(p.offNetworkJson())) kids |= KIDS.matcher(o.path("relation").asString("")).find();
    var person = new Person(a.id(), a.handle(), a.displayName(), false, gender, a.birthDate(), circles, traits, quirks,
        interests, politics, biases, taste, temper, routine, id.path("faith").asString(null),
        Observance.valueOf(id.path("observance").asString("NONE")), kids, p.pinned());
    person.status = StatusKind.valueOf(a.relationshipStatus());
    String partner = id.path("partner").asString(null);
    person.partnerId = partner == null ? null : idByHandle.get(partner);
    return person;
  }

  private static double[] curve(JsonNode n) {
    if (!n.isArray() || n.size() != 24) return flat();
    var out = new double[24];
    for (int i = 0; i < 24; i++) out[i] = n.get(i).asDouble();
    return out;
  }

  private static double[] flat() {
    var out = new double[24];
    for (int i = 0; i < 24; i++) out[i] = i >= 7 && i <= 23 ? 0.3 : 0.02;
    return out;
  }

  /** Every changed row of {@code world}, plus the world row; clears the dirty marks. */
  public UnitOfWork write(UnitOfWork uow, SimWorld world) {
    var states = new ArrayList<ActorStateRow>();
    for (var p : world.people.values()) {
      if (p.dirtyActor) {
        uow.add("UPDATE actor SET relationship_status = ? WHERE id = ?", p.status.name(), p.id);
        p.dirtyActor = false;
      }
      if (p.dirtyState && !p.user) {
        states.add(new ActorStateRow(p.id, p.mood, p.energy, p.stress, p.partnerSatisfaction, Json.write(new TreeMap<>(p.traitOffsets)),
            p.location, p.interactionsToday, p.lastFeedCheck, p.stateAt, p.interactionsDay));
        p.dirtyState = false;
      }
    }
    new CastRepository(sql).saveStates(uow, states);

    var rels = new ArrayList<RelationshipRow>();
    for (var e : world.edges.values().stream().sorted(java.util.Comparator.comparing(x -> x.from + "|" + x.to)).toList()) {
      if (!e.dirty) continue;
      rels.add(new RelationshipRow(e.from, e.to, e.kind, round(e.affinity), round(e.trust), round(e.attraction),
          round(e.familiarity), round(e.respect), e.chemistry, Json.write(e.tags), e.summary, e.connected,
          e.lastInteraction, e.settledAt, Json.write(e.pending)));
      e.dirty = false;
    }
    new RelationshipRepository(sql).save(uow, rels);

    var arcs = new ArrayList<ArcRow>();
    var participants = new ArrayList<ArcRow.Participant>();
    for (var a : world.arcs.values()) {
      if (!a.dirty && !a.isNew) continue;
      arcs.add(new ArcRow(a.id, a.defId, a.status, a.phase, a.visibility, a.startedAt, a.phaseEnteredAt,
          a.nextTransitionAt, a.lastBeatAt, Json.write(a.data), a.summary, a.endedAt, a.endEventId));
      if (a.isNew) a.roles.forEach((role, actor) -> participants.add(new ArcRow.Participant(a.id, actor, role)));
      a.dirty = false;
      a.isNew = false;
    }
    var arcRepo = new ArcRepository(sql);
    arcRepo.save(uow, arcs);
    arcRepo.saveParticipants(uow, participants);

    var entities = new ArrayList<EntityRow>();
    for (var t : world.things.values()) {
      if (!t.dirty) continue;
      entities.add(new EntityRow(t.id, t.ownerId, t.kind, t.name, t.visualDescription, t.imageSeed,
          Json.write(t.attributes), t.status, t.createdAt, t.updatedAt));
      t.dirty = false;
    }
    var canon = new CanonRepository(sql);
    canon.saveEntities(uow, entities);

    var constraints = new ArrayList<ConstraintRow>();
    world.tags.forEach((actor, m) -> m.values().forEach(t -> constraints.add(
        new ConstraintRow(actor, t.tag(), t.since(), t.until(), t.sourceEventId()))));
    var repo = new ConstraintRepository(sql);
    for (String k : world.removedTags) {
      int i = k.indexOf('|');
      repo.delete(uow, k.substring(0, i), k.substring(i + 1));
    }
    world.removedTags.clear();
    repo.save(uow, constraints);

    var knowledge = new ArrayList<KnowledgeRow>();
    for (String k : world.newKnowledge) {
      var parts = k.split(":", 3);
      knowledge.add(new KnowledgeRow(KnowledgeRow.Subject.valueOf(parts[0]), parts[1], parts[2], world.time));
    }
    world.newKnowledge.clear();
    canon.saveKnowledge(uow, knowledge);

    uow.add("UPDATE world SET committed_until = ?, tension = ?, last_session_at = ? WHERE id = ?", world.time,
        round(world.tension), world.lastSessionAt, Schema.WORLD_ID);
    return uow;
  }

  /** Four decimals: enough for meters, stable across platforms. */
  static double round(double v) {
    return Math.round(v * 10_000) / 10_000.0;
  }
}
