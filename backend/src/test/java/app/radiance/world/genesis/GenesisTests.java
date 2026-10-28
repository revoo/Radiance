package app.radiance.world.genesis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.radiance.cast.CastRepository;
import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.graph.RelationshipRepository;
import app.radiance.kernel.ManualClock;
import app.radiance.llm.Budget;
import app.radiance.llm.DefaultLlmGateway;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.LlmCallLog;
import app.radiance.llm.LlmProperties;
import app.radiance.llm.RouteTable;
import app.radiance.llm.provider.FakeLlm;
import app.radiance.persistence.TestDatabase;
import app.radiance.persistence.UnitOfWork;
import app.radiance.story.ArcRepository;
import app.radiance.story.CanonRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenesisTests {

  static final ContentLibrary LIB = ClasspathContent.load(List.of(new ContentProperties.BlacklistEntry(
      "pregnancy", List.of("pregnant", "pregnancy", "expecting a baby", "baby bump", "trimester",
          "baby shower", "gender reveal"))));

  @TempDir Path dir;
  final FakeResponses fakes = new FakeResponses();
  final LlmCallLog.InMemory calls = new LlmCallLog.InMemory();

  DefaultLlmGateway gateway() {
    var props = new LlmProperties(LlmProperties.Mode.FAKE, null, null, null, 0, null, false, null);
    return new DefaultLlmGateway(RouteTable.build(props), 24_000,
        Map.of(RouteTable.FAKE_PROVIDER, new FakeLlm(fakes)), Budget.UNLIMITED, calls,
        new SimpleMeterRegistry(), new ManualClock(TestDatabase.START), ms -> { }, p -> "");
  }

  static WorldSeed skeleton() {
    return SkeletonGenerator.generate(LIB,
        new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
  }

  WorldSeed fleshed() {
    GenesisFakes.register(fakes);
    return new FleshOut(gateway(), LIB, "Radiance").run(skeleton(), s -> { });
  }

  @Test
  void fleshOutFillsEveryResidentAndEveryTieInBatchesOfFive() {
    var checkpoints = new ArrayList<WorldSeed>();
    GenesisFakes.register(fakes);
    var s = new FleshOut(gateway(), LIB, "Radiance").run(skeleton(), checkpoints::add);

    assertThat(s.residents()).allSatisfy(r -> {
      assertThat(r.bio()).isNotBlank();
      assertThat(r.backstory()).contains(r.livesIn());
      assertThat(r.voice().samplePosts()).hasSize(3);
      assertThat(r.voice().sampleComments()).hasSize(2);
    });
    assertThat(s.relationships()).allSatisfy(e -> assertThat(e.backstory()).isNotBlank());
    long residentCalls = calls.entries().stream().filter(e -> e.attempt() == 1).count();
    assertThat(residentCalls).as("6 resident batches plus tie batches").isGreaterThan(6);
    assertThat(checkpoints.get(0).residents().stream().filter(r -> r.bio() != null)).hasSize(5);
    // the cached prefix is the same for every genesis call (world bible + cast directory)
    var prefix = new FleshOut(gateway(), LIB, "Radiance").prefix(s);
    assertThat(prefix.get(0).text()).contains("Chicagoland", "pregnant, pregnancy").doesNotContain("{{");
    assertThat(prefix.get(1).text()).contains("@sarah.brennan — Sarah Brennan, 33, Marketing manager at Lakeshore Mutual");
  }

  @Test
  void proseThatMentionsABlacklistedThemeIsRegenerated() {
    var attempts = new AtomicInteger();
    fakes.register(FleshOut.Batch.class, call -> {
      var batch = GenesisFakes.batch(call.allContent());
      if (attempts.getAndIncrement() > 0) return batch;
      var first = batch.residents().getFirst();
      var poisoned = new FleshOut.Person(first.handle(), "Mom of two, expecting a baby in May!", first.backstory(),
          first.voice(), first.values(), first.fears(), first.desires(), first.entities(), first.appearance());
      var people = new ArrayList<>(batch.residents());
      people.set(0, poisoned);
      return new FleshOut.Batch(people);
    });
    fakes.register(FleshOut.Ties.class, call -> GenesisFakes.ties(call.allContent()));
    var s = new FleshOut(gateway(), LIB, "Radiance").run(skeleton(), x -> { });
    assertThat(s.residents()).allSatisfy(r -> assertThat(LIB.themeLinter().hits(r.bio())).isEmpty());
    assertThat(attempts.get()).as("the poisoned resident was asked for again").isEqualTo(7);
  }

  /** A person as authored JSON, with a chosen bio and entity looks. */
  static String personJson(String handle, String bio, String entityKey) {
    return """
        {"handle":"%s","bio":"%s","backstory":"Lives here.","appearance":"30s, medium build.",
         "voice":{"lengthBand":"SHORT","emojiLevel":"LOW","casing":"STANDARD","punctuation":"plain",
                  "typoRate":0.02,"slang":[],"catchphrases":[],"signatureTopics":[],"neverDoes":[],
                  "samplePosts":["a","b","c"],"sampleComments":["d","e"]},
         "values":["v"],"fears":["f"],"desires":["d"],
         "entities":[%s]}""".formatted(handle, bio,
        entityKey == null ? "" : "{\"key\":\"" + entityKey + "\",\"visualDescription\":\"a thing\"}");
  }

  @Test
  void authoredProseGetsTheSameChecksAsAModelsReply() {
    var s = skeleton();
    // schema errors name the file
    assertThatThrownBy(() -> FleshOut.Source.authored(Map.of("residents-1.json",
            "{\"residents\":[{\"handle\":\"x\"}]}"), "test"))
        .hasMessageContaining("residents-1.json: $.residents[0].bio: missing");
    // the same person twice is an authoring mistake
    String one = "{\"residents\":[" + personJson("jake.zielinski", "Kinzie.", null) + "]}";
    assertThatThrownBy(() -> FleshOut.Source.authored(Map.of("residents-1.json", one, "residents-2.json", one), "test"))
        .hasMessageContaining("@jake.zielinski appears twice");

    // blacklisted themes and someone else's entity are rejected, and there's no model to ask again
    var poisoned = FleshOut.Source.authored(Map.of("residents-1.json", "{\"residents\":["
        + personJson("jake.zielinski", "Expecting a baby in May", null) + "]}"), "test");
    assertThatThrownBy(() -> new FleshOut(poisoned, LIB, "Radiance").regenerate(s, "jake.zielinski"))
        .hasMessageContaining("unusable for [jake.zielinski]");
    var stolen = FleshOut.Source.authored(Map.of("residents-1.json", "{\"residents\":["
        + personJson("jake.zielinski", "Kinzie.", "home:samantha.dvorak") + "]}"), "test");
    assertThatThrownBy(() -> new FleshOut(stolen, LIB, "Radiance").regenerate(s, "jake.zielinski"))
        .hasMessageContaining("unusable for [jake.zielinski]");

    // a clean authored resident merges, with appearance and provenance
    var clean = FleshOut.Source.authored(Map.of("residents-1.json", one,
        "ties-1.json", "{\"pairs\":[{\"a\":\"jake.zielinski\",\"b\":\"kendra.gaines\",\"story\":\"Exes.\"}]}"),
        "authored in a test");
    var after = new FleshOut(clean, LIB, "Radiance").regenerate(s, "jake.zielinski");
    var jake = after.residents().stream().filter(r -> r.handle().equals("jake.zielinski")).findFirst().orElseThrow();
    assertThat(jake.bio()).isEqualTo("Kinzie.");
    assertThat(jake.appearance()).isEqualTo("30s, medium build.");
  }

  @Test
  void regeneratingOneResidentKeepsTheirIdentity() {
    var s = fleshed();
    var before = s.residents().stream().filter(r -> r.handle().equals("jake.zielinski")).findFirst().orElseThrow();
    var after = new FleshOut(gateway(), LIB, "Radiance").regenerate(s, "jake.zielinski").residents().stream()
        .filter(r -> r.handle().equals("jake.zielinski")).findFirst().orElseThrow();
    assertThat(after.name()).isEqualTo(before.name());
    assertThat(after.quirks()).contains("troll");
    assertThat(after.bio()).isNotNull();
    assertThatThrownBy(() -> new FleshOut(gateway(), LIB, "Radiance").regenerate(s, "nobody"))
        .hasMessageContaining("no resident 'nobody'");
  }

  @Test
  void importYieldsTheRostersThirtyResidentsTheirTiesAndAtLeastFiveArcs() {
    var s = fleshed();
    try (var db = TestDatabase.leased(dir)) {
      var result = new SeedImporter(db.sql, db.lane, LIB).importSeed(s, TestDatabase.START);

      var actors = new CastRepository(db.sql).actors();
      assertThat(actors).hasSize(30);
      for (var entry : LIB.roster().residents()) {
        var a = actors.stream().filter(x -> x.handle().equals(entry.resolvedHandle())).findFirst().orElseThrow();
        assertThat(a.displayName()).isEqualTo(entry.shownName());
        assertThat(a.gender()).isEqualTo(entry.gender().name());
        assertThat(a.relationshipStatus()).isEqualTo(entry.status().kind().name());
      }
      assertThat(new RelationshipRepository(db.sql).all()).hasSize(result.relationships()).hasSizeBetween(150, 300);
      var arcs = new ArcRepository(db.sql).active();
      assertThat(arcs).hasSizeGreaterThanOrEqualTo(5);
      assertThat(arcs).extracting(a -> a.defId() + ":" + a.phase())
          .contains("romance:official", "romance:secret_dating", "home_project:progress");
      assertThat(arcs).allSatisfy(a -> assertThat(a.nextTransitionAt()).isGreaterThan(a.phaseEnteredAt()));

      // Jessica's secret is known to the couple only (decision 6)
      var secret = arcs.stream().filter(a -> a.phase().equals("secret_dating")).findFirst().orElseThrow();
      var knowers = new CanonRepository(db.sql).knowledge().stream()
          .filter(k -> k.subjectId().equals(secret.id())).map(k -> k.actorId()).toList();
      var megan = actors.stream().filter(a -> a.handle().equals("megan.quinlan")).findFirst().orElseThrow();
      assertThat(knowers).hasSize(2).doesNotContain(megan.id());
      assertThat(new CanonRepository(db.sql).liveFacts()).hasSize(result.facts())
          .anyMatch(f -> f.statement().startsWith("has a wife,"));

      // importing the same file again changes nothing
      new SeedImporter(db.sql, db.lane, LIB).importSeed(s, TestDatabase.START + 1000);
      assertThat(new CastRepository(db.sql).actors()).hasSize(30);
      assertThat(new RelationshipRepository(db.sql).all()).hasSize(result.relationships());
    }
  }

  @Test
  void importRefusesSeedsThatChangeTheRosterOrClobberAWorld() {
    var s = fleshed();
    var renamed = new ArrayList<>(s.residents());
    var jake = renamed.stream().filter(r -> r.handle().equals("jake.zielinski")).findFirst().orElseThrow();
    renamed.set(renamed.indexOf(jake), new WorldSeed.Resident(jake.handle(), "Jacob Zielinski", jake.displayName(),
        jake.gender(), jake.birthDate(), jake.heritage(), jake.faithTradition(), jake.observance(), jake.livesIn(),
        jake.occupation(), jake.employer(), jake.status(), jake.partner(), jake.circles(), jake.pinned(),
        jake.archetype(), jake.traits(), jake.quirks(), jake.interests(), jake.politics(), jake.biases(),
        jake.taste(), jake.temperament(), jake.routine(), jake.state(), jake.offNetwork(), jake.bio(),
        jake.backstory(), jake.voice(), jake.values(), jake.fears(), jake.desires(), jake.appearance()));
    var bad = new WorldSeed(s.version(), s.seed(), s.referenceDate(), renamed, s.relationships(), s.arcs(), s.entities(),
        s.provenance());

    try (var db = TestDatabase.leased(dir)) {
      var importer = new SeedImporter(db.sql, db.lane, LIB);
      assertThatThrownBy(() -> importer.importSeed(bad, TestDatabase.START))
          .hasMessageContaining("@jake.zielinski was renamed to 'Jacob Zielinski'");

      importer.importSeed(s, TestDatabase.START);
      var other = SkeletonGenerator.generate(LIB, new SkeletonGenerator.Settings(8, 3, 5, 4, LocalDate.of(2026, 10, 27)));
      assertThatThrownBy(() -> importer.importSeed(other, TestDatabase.START))
          .hasMessageContaining("different genesis seed");

      var actorId = new CastRepository(db.sql).actors().getFirst().id();
      db.commit(new UnitOfWork().add("INSERT INTO post (id, author_id, created_at, kind, audience, origin,"
          + " air_status, render_status, importance, fidelity) VALUES ('p1', ?, 1, 'STATUS', 'FRIENDS', 'USER',"
          + " 'AIRED', 'RENDERED', 1, 'FULL')", actorId));
      assertThatThrownBy(() -> importer.importSeed(s, TestDatabase.START))
          .hasMessageContaining("already has content");
    }
  }

  @Test
  void seedFilesRoundTrip() {
    var s = fleshed();
    var file = dir.resolve("data/world-seed.json");
    SeedFiles.write(file, s);
    assertThat(SeedFiles.read(file)).isEqualTo(s);
    assertThat(dir.resolve("data/world-seed.md")).content().contains("### Sarah Brennan ★", "Known only to Jessica Vogel and");
  }
}
