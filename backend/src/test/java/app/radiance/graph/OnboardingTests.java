package app.radiance.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.radiance.cast.ActorRow;
import app.radiance.cast.CastRepository;
import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.kernel.Json;
import app.radiance.kernel.ManualClock;
import app.radiance.llm.Budget;
import app.radiance.llm.DefaultLlmGateway;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.LlmCallLog;
import app.radiance.llm.LlmProperties;
import app.radiance.llm.RouteTable;
import app.radiance.llm.provider.FakeLlm;
import app.radiance.persistence.TestDatabase;
import app.radiance.world.genesis.FleshOut;
import app.radiance.world.genesis.GenesisFakes;
import app.radiance.world.genesis.SeedImporter;
import app.radiance.world.genesis.SkeletonGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnboardingTests {

  static final ContentLibrary LIB = ClasspathContent.load(List.of(
      new ContentProperties.BlacklistEntry("pregnancy", List.of("pregnant"))));

  @TempDir Path dir;
  TestDatabase db;
  Onboarding onboarding;

  @BeforeEach
  void world() {
    db = TestDatabase.leased(dir);
    var fakes = new FakeResponses();
    GenesisFakes.register(fakes);
    Onboarding.registerFake(fakes);
    var props = new LlmProperties(LlmProperties.Mode.FAKE, null, null, null, 0, null, false, null);
    var gateway = new DefaultLlmGateway(RouteTable.build(props), 24_000,
        Map.of(RouteTable.FAKE_PROVIDER, new FakeLlm(fakes)), Budget.UNLIMITED, new LlmCallLog.InMemory(),
        new SimpleMeterRegistry(), new ManualClock(TestDatabase.START), ms -> { }, p -> "");
    var seed = new FleshOut(gateway, LIB, "Radiance").run(SkeletonGenerator.generate(LIB,
        new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27))), s -> { });
    new SeedImporter(db.sql, db.lane, LIB).importSeed(seed, TestDatabase.START);
    onboarding = new Onboarding(db.sql, db.lane, gateway, LIB, 1.0);
  }

  @AfterEach
  void close() {
    db.close();
  }

  Onboarding.Profile profile(List<String> circles, Onboarding.Backstory backstory) {
    return new Onboarding.Profile("Russ Kowalczyk", "Writes code, eats beef sandwiches.", "a fintech startup",
        "Logan Square, Chicago", circles, List.of("tech", "food"), backstory);
  }

  @Test
  void localBackstoryConnectsAboutTwentyResidentsStartingWithRsCircles() {
    var result = onboarding.onboard(profile(List.of("softball"), Onboarding.Backstory.LOCAL), 7, TestDatabase.START);
    assertThat(result.connections()).isEqualTo(20);
    assertThat(result.handle()).isEqualTo("russ.kowalczyk");

    var actors = new CastRepository(db.sql).actors().stream().collect(Collectors.toMap(ActorRow::id, a -> a));
    var toR = new RelationshipRepository(db.sql).toward(result.actorId());
    var connected = toR.stream().filter(RelationshipRow::connected).toList();
    assertThat(connected).hasSize(20);
    var softball = actors.values().stream()
        .filter(a -> a.kind() == ActorRow.Kind.BOT && Json.read(a.circlesJson(), List.class).contains("softball"))
        .map(ActorRow::id).toList();
    assertThat(connected).extracting(RelationshipRow::fromId).containsAll(softball);

    // only women can become romantically interested in R; meters stay inside temperament bounds
    for (var e : toR) {
      var a = actors.get(e.fromId());
      if (a.gender().equals("M")) assertThat(e.attraction()).as(a.handle()).isZero();
      assertThat(e.affinity()).isBetween(-100.0, 100.0);
    }
    var personas = new CastRepository(db.sql).personas();
    for (var e : toR) {
      var t = Json.tree(personas.stream().filter(p -> p.actorId().equals(e.fromId())).findFirst().orElseThrow()
          .temperamentJson());
      assertThat(e.affinity()).isBetween(t.path("floor").asDouble(), t.path("ceiling").asDouble());
    }
  }

  @Test
  void newcomersStartSmallFamilyIsOffLimitsAndOnboardingHappensOnce() {
    assertThatThrownBy(() -> onboarding.onboard(profile(List.of("family"), null), 7, TestDatabase.START))
        .hasMessageContaining("except family");
    var result = onboarding.onboard(profile(List.of(), Onboarding.Backstory.NEWCOMER), 7, TestDatabase.START);
    assertThat(result.connections()).isEqualTo(4);
    assertThatThrownBy(() -> onboarding.onboard(profile(List.of(), null), 7, TestDatabase.START))
        .hasMessageContaining("already onboarded");
  }

}
