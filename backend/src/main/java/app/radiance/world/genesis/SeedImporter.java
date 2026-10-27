package app.radiance.world.genesis;

import app.radiance.cast.ActorRow;
import app.radiance.cast.ActorStateRow;
import app.radiance.cast.CastRepository;
import app.radiance.cast.PersonaRow;
import app.radiance.content.ContentLibrary;
import app.radiance.graph.RelationshipRepository;
import app.radiance.graph.RelationshipRow;
import app.radiance.kernel.Json;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Ulid;
import app.radiance.kernel.Visibility;
import app.radiance.persistence.Schema;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.story.ArcRepository;
import app.radiance.story.ArcRow;
import app.radiance.story.CanonRepository;
import app.radiance.story.EntityRow;
import app.radiance.story.FactRow;
import app.radiance.story.KnowledgeRow;
import app.radiance.world.WorldRepository;
import app.radiance.world.WorldRow;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Genesis step 4's commit (SPEC §6.4): checks a {@link WorldSeed} against the roster, then writes the
 * world in one unit through the writer lane. IDs are derived from the seed and each item's key, so
 * importing the same file twice changes nothing. Refuses once the world has content: genesis starts a
 * world; it doesn't overwrite one.
 */
public final class SeedImporter {

  public record Result(int residents, int relationships, int arcs, int entities, int facts) {}

  private static final long DAY = 86_400_000L;

  private final Sql sql;
  private final WriterLane lane;
  private final ContentLibrary lib;

  public SeedImporter(Sql sql, WriterLane lane, ContentLibrary lib) {
    this.sql = sql;
    this.lane = lane;
    this.lib = lib;
  }

  /** @param now world time at import; the world starts here */
  public Result importSeed(WorldSeed seed, long now) {
    validate(seed);
    long posts = sql.queryOne("SELECT COUNT(*) AS n FROM post", List.of(), r -> r.lng("n")).orElse(0L);
    if (posts > 0) {
      throw new IllegalStateException("the world already has content; genesis only starts a new world");
    }
    var ids = new Ids(seed);
    var existing = sql.query("SELECT id, handle FROM actor WHERE kind = 'BOT'", List.of(),
        r -> Map.entry(r.str("handle"), r.str("id")));
    for (var e : existing) {
      if (!e.getValue().equals(ids.actor(e.getKey()))) {
        throw new IllegalStateException("residents from a different genesis seed are already imported;"
            + " start from an empty database to import seed " + seed.seed());
      }
    }
    var world = new WorldRepository(sql).load()
        .orElseThrow(() -> new IllegalStateException("no world row; the app bootstraps one at startup"));
    var uow = new UnitOfWork();

    new WorldRepository(sql).save(uow, new WorldRow(Schema.WORLD_ID, world.name(), world.networkName(),
        world.timezone(), seed.seed(), now, now, 0, false, 0, null, world.settingsJson(), now));

    var actors = new ArrayList<ActorRow>();
    var personas = new ArrayList<PersonaRow>();
    var states = new ArrayList<ActorStateRow>();
    var facts = new ArrayList<FactRow>();
    for (var r : seed.residents()) {
      String id = ids.actor(r.handle());
      actors.add(new ActorRow(id, ActorRow.Kind.BOT, r.handle(), r.displayName() != null ? r.displayName() : r.name(),
          null, null, r.birthDate(), r.gender().name(), r.occupation(), r.employer(), null, r.livesIn(),
          r.status().name(), r.bio(), Json.write(r.circles()), now));
      var identity = new LinkedHashMap<String, Object>();
      identity.put("name", r.name());
      identity.put("heritage", r.heritage());
      identity.put("faith", r.faithTradition());
      identity.put("observance", r.observance().name());
      identity.put("archetype", r.archetype());
      identity.put("partner", r.partner());
      personas.add(new PersonaRow(id, Json.write(r.traits()), Json.write(r.quirks()), Json.write(r.interests()),
          Json.write(r.politics()), Json.write(r.biases()), Json.write(r.taste()), Json.write(r.temperament()),
          Json.write(r.voice() == null ? Map.of() : r.voice()), Json.write(r.routine()),
          Json.write(r.values() == null ? List.of() : r.values()),
          Json.write(r.fears() == null ? List.of() : r.fears()),
          Json.write(r.desires() == null ? List.of() : r.desires()), Json.write(r.offNetwork()),
          Json.write(identity), r.backstory() == null ? "" : r.backstory(), null, r.pinned()));
      var s = r.state();
      states.add(new ActorStateRow(id, s.mood(), s.energy(), s.stress(), s.partnerSatisfaction(), "{}", "HOME", 0,
          null, now));
      for (var p : r.offNetwork()) {
        facts.add(new FactRow(ids.of("fact:" + r.handle() + ":" + p.relation() + ":" + p.name()), id,
            "has " + article(p.relation()) + p.relation() + ", " + p.name() + (p.age() != null ? " (" + p.age() + ")" : "")
                + (p.note() != null ? ", who " + p.note() : ""),
            FactRow.Weight.MAJOR, FactRow.Source.GENESIS, FactRow.Status.ACTIVE, Visibility.PUBLIC, null, now, null,
            null));
      }
    }
    var cast = new CastRepository(sql);
    cast.saveActors(uow, actors);
    cast.savePersonas(uow, personas);
    cast.saveStates(uow, states);

    var rels = new ArrayList<RelationshipRow>();
    for (var e : seed.relationships()) {
      rels.add(new RelationshipRow(ids.actor(e.from()), ids.actor(e.to()), RelationshipRow.Kind.valueOf(e.kind()),
          e.affinity(), e.trust(), e.attraction(), e.familiarity(), e.respect(), e.chemistry(),
          Json.write(e.tags()), e.backstory(), e.connected(), null, now));
    }
    new RelationshipRepository(sql).save(uow, rels);

    var entities = new ArrayList<EntityRow>();
    for (var e : seed.entities()) {
      entities.add(new EntityRow(ids.of(e.key()), ids.actor(e.owner()), EntityRow.Kind.valueOf(e.kind().name()),
          e.name(), e.visualDescription(), e.imageSeed(), Json.write(e.attributes()), e.status(), now, now));
    }
    var canon = new CanonRepository(sql);
    canon.saveEntities(uow, entities);

    var arcs = new ArrayList<ArcRow>();
    var participants = new ArrayList<ArcRow.Participant>();
    var knowledge = new ArrayList<KnowledgeRow>();
    for (var a : seed.arcs()) {
      String id = ids.of(a.key());
      var def = lib.arcs().get(a.defId());
      var phase = def.phases().stream().filter(p -> p.id().equals(a.phase())).findFirst().orElseThrow();
      long entered = now - a.daysIntoPhase() * DAY;
      Long next = null;
      if (!phase.terminal() && phase.durationDays().size() == 2) {
        var rng = RandomSource.seeded(seed.seed()).derive("arc-next:" + a.key());
        int min = Math.max(phase.durationDays().get(0), a.daysIntoPhase() + 1);
        int max = Math.max(min, phase.durationDays().get(1));
        next = entered + (min + rng.nextInt(max - min + 1)) * DAY;
      }
      var data = new LinkedHashMap<String, Object>();
      a.roles().forEach((role, h) -> data.put(role, ids.actor(h)));
      a.data().forEach((k, v) -> data.put(k, k.equals("entity") ? ids.of(String.valueOf(v))
          : k.equals("partner") ? ids.actor(String.valueOf(v)) : v));
      String names = a.roles().values().stream().map(h -> nameOf(seed, h)).collect(Collectors.joining(" and "));
      arcs.add(new ArcRow(id, a.defId(), ArcRow.Status.ACTIVE, a.phase(), a.visibility(), entered, entered, next,
          null, Json.write(data), names + ": " + a.defId().replace('_', ' ') + " (" + a.phase().replace('_', ' ') + ")",
          null, null));
      a.roles().forEach((role, h) -> participants.add(new ArcRow.Participant(id, ids.actor(h), role)));
      for (String h : a.knowers()) knowledge.add(new KnowledgeRow(KnowledgeRow.Subject.ARC, id, ids.actor(h), entered));
    }
    var arcRepo = new ArcRepository(sql);
    arcRepo.save(uow, arcs);
    arcRepo.saveParticipants(uow, participants);
    canon.saveKnowledge(uow, knowledge);
    canon.saveFacts(uow, facts);

    lane.run(() -> sql.commit(uow));
    return new Result(actors.size(), rels.size(), arcs.size(), entities.size(), facts.size());
  }

  private static String article(String relation) {
    return "aeiou".indexOf(relation.charAt(0)) >= 0 ? "an " : "a ";
  }

  private static String nameOf(WorldSeed seed, String handle) {
    return seed.residents().stream().filter(r -> r.handle().equals(handle)).findFirst()
        .map(r -> r.displayName() != null ? r.displayName() : r.name()).orElse(handle);
  }

  /** The roster is fixed (SPEC §6.2): the seed may add prose, never change who people are. */
  void validate(WorldSeed seed) {
    var problems = new ArrayList<String>();
    var ref = LocalDate.parse(seed.referenceDate());
    var byHandle = new HashMap<String, WorldSeed.Resident>();
    for (var r : seed.residents()) byHandle.put(r.handle(), r);
    var roster = lib.roster().residents();
    if (seed.residents().size() != roster.size()) {
      problems.add("the seed has " + seed.residents().size() + " residents; the roster has " + roster.size());
    }
    for (var entry : roster) {
      var r = byHandle.get(entry.resolvedHandle());
      if (r == null) {
        problems.add("missing roster resident " + entry.name() + " (@" + entry.resolvedHandle() + ")");
        continue;
      }
      if (!r.name().equals(entry.name())) problems.add("@" + r.handle() + " was renamed to '" + r.name() + "'");
      if (r.gender() != entry.gender()) problems.add("@" + r.handle() + "'s gender changed");
      if (Period.between(LocalDate.parse(r.birthDate()), ref).getYears() != entry.age()) {
        problems.add("@" + r.handle() + "'s birth date doesn't match age " + entry.age());
      }
      if (r.status() != entry.status().kind()) problems.add("@" + r.handle() + "'s relationship status changed");
    }
    for (var e : seed.relationships()) {
      if (!byHandle.containsKey(e.from()) || !byHandle.containsKey(e.to())) {
        problems.add("relationship " + e.from() + " -> " + e.to() + " names someone not in the seed");
        continue;
      }
      if (e.attraction() > 0 && byHandle.get(e.from()).gender() == byHandle.get(e.to()).gender()) {
        problems.add("attraction between " + e.from() + " and " + e.to() + " breaks invariant 11");
      }
    }
    for (var a : seed.arcs()) {
      if (lib.isBlacklisted(a.defId())) problems.add("arc " + a.key() + " is blacklisted");
      var def = lib.arcs().get(a.defId());
      if (def == null) {
        problems.add("arc " + a.key() + " has unknown def '" + a.defId() + "'");
      } else if (def.phases().stream().noneMatch(p -> p.id().equals(a.phase()))) {
        problems.add("arc " + a.key() + " has unknown phase '" + a.phase() + "'");
      }
      a.roles().values().forEach(h -> {
        if (!byHandle.containsKey(h)) problems.add("arc " + a.key() + " names unknown resident @" + h);
      });
    }
    var linter = lib.themeLinter();
    for (var r : seed.residents()) {
      String text = String.join("\n", r.bio() == null ? "" : r.bio(), r.backstory() == null ? "" : r.backstory());
      if (!linter.hits(text).isEmpty()) problems.add("@" + r.handle() + "'s prose mentions a blacklisted theme");
    }
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException("world-seed.json can't be imported:\n  - " + String.join("\n  - ", problems));
    }
  }

  /** Stable ids: a ULID from the reference date and a stream keyed by seed and item key. */
  private static final class Ids {
    private final WorldSeed seed;
    private final long t;
    private final Map<String, String> cache = new HashMap<>();

    Ids(WorldSeed seed) {
      this.seed = seed;
      this.t = LocalDate.parse(seed.referenceDate()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    String actor(String handle) {
      return of("actor:" + handle);
    }

    String of(String key) {
      return cache.computeIfAbsent(key, k -> Ulid.next(t, RandomSource.seeded(seed.seed()).derive("id:" + k)));
    }
  }
}
