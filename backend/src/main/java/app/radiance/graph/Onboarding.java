package app.radiance.graph;

import app.radiance.cast.ActorRow;
import app.radiance.cast.CastRepository;
import app.radiance.cast.PersonaRow;
import app.radiance.content.ContentLibrary;
import app.radiance.kernel.Json;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Ulid;
import app.radiance.llm.Block;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.LlmRequest;
import app.radiance.llm.Part;
import app.radiance.llm.Priority;
import app.radiance.llm.Purpose;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * R joins the world (SPEC §5.9): his profile from the roster's {@code you} entry plus what he tells
 * onboarding, one appraisal of that profile, and first impressions — every resident's initial edge
 * toward him — plus his starting connections, drawn first from the circles he picked.
 */
public final class Onboarding {

  public enum Backstory {
    NEWCOMER(4), REGULAR(10), LOCAL(20);

    final int connections;

    Backstory(int connections) {
      this.connections = connections;
    }
  }

  /** What onboarding asks for; the rest is pre-filled from the roster's {@code you} entry. */
  public record Profile(
      String name, String bio, String employer, String livesIn, List<String> circles,
      List<String> interests, Backstory backstory) {}

  /** The appraisal of R's profile (the {@code appraisal} route): tone features 0–1 and politics. */
  public record ProfileAppraisal(
      double humor, double warmth, double intellect, double pretentiousness, double bragging,
      double vulnerability, double positivity, double edginess, double preachiness, double effort,
      double politicalEconomic, double politicalSocial, double politicalConfidence, List<String> topics) {}

  public record Result(String actorId, String handle, int connections, int impressions) {}

  private final Sql sql;
  private final WriterLane lane;
  private final LlmGateway gateway;
  private final ContentLibrary lib;
  private final double crushPropensity;

  public Onboarding(Sql sql, WriterLane lane, LlmGateway gateway, ContentLibrary lib, double crushPropensity) {
    this.sql = sql;
    this.lane = lane;
    this.gateway = gateway;
    this.lib = lib;
    this.crushPropensity = crushPropensity;
  }

  /** A neutral, deterministic appraisal for offline play. */
  public static void registerFake(FakeResponses fakes) {
    fakes.register(ProfileAppraisal.class, call -> new ProfileAppraisal(0.4, 0.5, 0.5, 0.2, 0.1, 0.2, 0.5,
        0.2, 0.1, 0.5, 0, 0, 0.1, List.of("tech")));
  }

  public Result onboard(Profile p, long worldSeed, long now) {
    var cast = new CastRepository(sql);
    var actors = cast.actors();
    if (actors.stream().anyMatch(a -> a.kind() == ActorRow.Kind.USER)) {
      throw new IllegalStateException("R is already onboarded; edit the profile instead");
    }
    var residents = actors.stream().filter(a -> a.kind() == ActorRow.Kind.BOT)
        .sorted(Comparator.comparing(ActorRow::handle)).toList();
    if (residents.isEmpty()) throw new IllegalStateException("run genesis first: the world has no residents");
    for (String c : p.circles()) {
      if (c.equals("family")) throw new IllegalArgumentException("R can join any circle except family");
      if (!app.radiance.content.Vocab.CIRCLES.contains(c)) throw new IllegalArgumentException("unknown circle " + c);
    }
    var you = lib.roster().you();
    var rng = RandomSource.seeded(worldSeed).derive("onboarding");
    String handle = app.radiance.content.Roster.handleFor(p.name());
    String id = Ulid.next(now, rng.derive("id"));
    String birth = LocalDate.ofEpochDay(now / 86_400_000L).minusYears(you.age()).minusDays(1 + rng.nextInt(364)).toString();
    var r = new ActorRow(id, ActorRow.Kind.USER, handle, p.name(), null, null, birth, you.gender().name(),
        you.work().title(), p.employer(), null, p.livesIn(), you.status().kind().name(), p.bio(),
        Json.write(p.circles()), now);

    var appraisal = appraise(p, you);
    var personas = cast.personas().stream().collect(java.util.stream.Collectors.toMap(PersonaRow::actorId, x -> x));
    var connected = pickConnections(p, residents, rng);

    var rels = new ArrayList<RelationshipRow>();
    for (var a : residents) {
      var persona = personas.get(a.id());
      boolean conn = connected.contains(a.id());
      var shared = circles(a);
      shared.retainAll(p.circles());
      if (!conn && shared.isEmpty() && !rng.derive("aware:" + a.id()).chance(0.25)) continue; // a stranger
      rels.add(impression(a, persona, appraisal, shared, conn, id, rng.derive("impression:" + a.id()), now));
      rels.add(new RelationshipRow(id, a.id(), conn ? kindFor(shared) : RelationshipRow.Kind.STRANGER, 0, 50, 0,
          conn ? 30 : 5, 0, rels.getLast().chemistry(), "[]", null, conn, null, now));
    }
    var uow = new UnitOfWork();
    cast.saveActors(uow, List.of(r));
    new RelationshipRepository(sql).save(uow, rels);
    lane.run(() -> sql.commit(uow));
    return new Result(id, handle, connected.size(), rels.size() / 2);
  }

  private ProfileAppraisal appraise(Profile p, app.radiance.content.Roster.You you) {
    String text = "Profile of a new member:\nName: " + p.name() + "\nAge: " + you.age() + "\nWork: "
        + you.work().title() + (p.employer() != null ? " at " + p.employer() : "") + "\nLives in: " + p.livesIn()
        + "\nBio: " + (p.bio() == null ? "" : p.bio()) + "\nInterests: " + String.join(", ", p.interests())
        + "\n\nRate how this profile comes across, each feature 0–1. Political axes −1…1 with your confidence 0–1."
        + " Topics from: " + String.join(", ", new java.util.TreeSet<>(app.radiance.content.Vocab.TOPICS)) + ".";
    var result = gateway.call(new LlmRequest<>(Purpose.APPRAISAL,
        List.of(new Block("You appraise social-network content neutrally and return structured features.")),
        List.of(Part.text(text)), ProfileAppraisal.class, 300, Duration.ofSeconds(30), Priority.LIVE));
    return result.ok() ? result.value()
        : new ProfileAppraisal(0.3, 0.3, 0.3, 0.2, 0.1, 0.1, 0.3, 0.1, 0.1, 0.3, 0, 0, 0, List.of());
  }

  /** Drawn first from R's circles, then the most sociable residents. */
  private List<String> pickConnections(Profile p, List<ActorRow> residents, RandomSource rng) {
    int want = (p.backstory() == null ? Backstory.LOCAL : p.backstory()).connections;
    var inCircles = new ArrayList<String>();
    var others = new ArrayList<String>();
    for (var a : residents) {
      var circles = circles(a);
      if (p.circles().stream().anyMatch(circles::contains)) inCircles.add(a.id());
      else others.add(a.id());
    }
    java.util.Collections.shuffle(inCircles, new java.util.Random(rng.derive("circles").nextLong()));
    java.util.Collections.shuffle(others, new java.util.Random(rng.derive("others").nextLong()));
    var out = new ArrayList<String>();
    for (String id : inCircles) if (out.size() < want) out.add(id);
    for (String id : others) if (out.size() < want) out.add(id);
    return out;
  }

  private static java.util.Set<String> circles(ActorRow a) {
    var out = new HashSet<String>();
    Json.tree(a.circlesJson()).forEach(n -> out.add(n.asString()));
    return out;
  }

  private static RelationshipRow.Kind kindFor(java.util.Set<?> shared) {
    if (shared.contains("lakeshore") || shared.contains("prairie-path") || shared.contains("kinzie")) {
      return RelationshipRow.Kind.COWORKER;
    }
    return shared.isEmpty() ? RelationshipRow.Kind.ACQUAINTANCE : RelationshipRow.Kind.FRIEND;
  }

  /** How resident {@code a} first sees R (SPEC §5.9 first impressions). */
  private RelationshipRow impression(ActorRow a, PersonaRow persona, ProfileAppraisal ap, java.util.Set<?> shared,
      boolean connected, String userId, RandomSource rng, long now) {
    int sharedCircles = shared.size();
    JsonNode traits = Json.tree(persona.traitsJson());
    JsonNode taste = Json.tree(persona.tasteJson());
    JsonNode temperament = Json.tree(persona.temperamentJson());
    double agree = traits.path("agreeableness").asDouble(50);
    double extra = traits.path("extraversion").asDouble(50);
    double open = traits.path("openness").asDouble(50);
    double features = taste.path("humor").asDouble(0) * ap.humor() + taste.path("warmth").asDouble(0) * ap.warmth()
        + taste.path("intellect").asDouble(0) * ap.intellect()
        + taste.path("pretentiousness").asDouble(0) * ap.pretentiousness()
        + taste.path("bragging").asDouble(0) * ap.bragging()
        + taste.path("vulnerability").asDouble(0) * ap.vulnerability()
        + taste.path("positivity").asDouble(0) * ap.positivity() + taste.path("edginess").asDouble(0) * ap.edginess()
        + taste.path("preachiness").asDouble(0) * ap.preachiness() + taste.path("effort").asDouble(0) * ap.effort();
    JsonNode politics = Json.tree(persona.politicsJson());
    double alignment = ap.politicalConfidence() * (1 - Math.abs(politics.path("economic").asDouble(0)
        - ap.politicalEconomic()) / 2) * politics.path("interest").asDouble(0);
    double chemistry = Math.max(-1, Math.min(1, rng.derive("chemistry").nextGaussian(0, 0.4)));
    double baseline = temperament.path("baseline").asDouble(10);
    double affinity = baseline + 0.25 * (agree - 50) + 0.12 * (extra - 50) + 0.08 * (open - 50) + 18 * features
        + 10 * alignment + 10 * chemistry + 4 * sharedCircles + rng.nextGaussian(0, 4);
    affinity = Math.max(temperament.path("floor").asDouble(-100), Math.min(temperament.path("ceiling").asDouble(100), affinity));
    // Only women can become romantically interested in R (SPEC §5.9; every resident is straight).
    double attraction = "F".equals(a.gender())
        ? Math.max(0, Math.min(100, (12 + 25 * chemistry + 15 * (ap.humor() + ap.warmth()) / 2) * crushPropensity
            + rng.nextGaussian(0, 6)))
        : 0;
    double familiarity = connected ? 15 + 12 * sharedCircles + rng.nextDouble(0, 10) : 3 + rng.nextDouble(0, 5);
    double respect = 10 + 20 * taste.path("intellect").asDouble(0) * ap.intellect() + rng.nextGaussian(0, 6);
    return new RelationshipRow(a.id(), userId, connected ? kindFor(shared) : RelationshipRow.Kind.STRANGER,
        round(affinity), 30 + round(rng.nextDouble(0, 15)), round(attraction), round(familiarity), round(respect),
        round(chemistry), "[]", null, connected, null, now);
  }

  private static double round(double v) {
    return Math.round(v * 100) / 100.0;
  }
}
