package app.radiance.world.genesis;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.content.Vocab.Gender;
import app.radiance.content.Vocab.Temperament;
import app.radiance.kernel.Json;
import app.radiance.kernel.Visibility;
import java.time.LocalDate;
import java.time.Period;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class SkeletonGeneratorTests {

  static final ContentLibrary LIB = ClasspathContent.load(List.of(
      new ContentProperties.BlacklistEntry("pregnancy", List.of("pregnant", "pregnancy"))));
  static final LocalDate REF = LocalDate.of(2026, 10, 27);

  static WorldSeed generate(long seed) {
    return SkeletonGenerator.generate(LIB, new SkeletonGenerator.Settings(seed, 3, 5, 4, REF));
  }

  static String json(WorldSeed s) {
    return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(s);
  }

  @Test
  void genesisSeven_isDeterministicAtTheSkeletonLevel() {
    assertThat(json(generate(7))).isEqualTo(json(generate(7)));
    assertThat(json(generate(7))).isNotEqualTo(json(generate(8)));
  }

  @Property(tries = 100) // 1,000 pass; CI draws different seeds every run
  void everySeedHonoursTheRosterAndTheGraphRules(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
    var s = generate(seed);
    var byHandle = s.residents().stream().collect(Collectors.toMap(WorldSeed.Resident::handle, r -> r));

    // the roster's identities, unchanged
    assertThat(s.residents()).hasSize(30);
    for (var entry : LIB.roster().residents()) {
      var r = byHandle.get(entry.resolvedHandle());
      assertThat(r.name()).isEqualTo(entry.name());
      assertThat(r.gender()).isEqualTo(entry.gender());
      assertThat(Period.between(LocalDate.parse(r.birthDate()), REF).getYears())
          .as(r.name() + "'s age").isEqualTo(entry.age());
      assertThat(r.quirks()).hasSizeBetween(1, 3);
    }

    // roughly 150–300 relationship rows; 5–12 meaningful ties each
    assertThat(s.relationships()).hasSizeBetween(150, 300);
    var meaningful = s.relationships().stream()
        .filter(e -> List.of("FRIEND", "CLOSE_FRIEND", "FAMILY", "PARTNER", "EX", "RIVAL").contains(e.kind())
            || e.familiarity() >= 40)
        .collect(Collectors.groupingBy(WorldSeed.Relationship::from, Collectors.counting()));
    for (var r : s.residents()) {
      assertThat(meaningful.getOrDefault(r.handle(), 0L)).as(r.handle() + " meaningful ties").isBetween(5L, 12L);
    }

    // invariant 11: attraction, crushes, partners and exes only ever pair a man and a woman
    for (var e : s.relationships()) {
      boolean opposite = byHandle.get(e.from()).gender() != byHandle.get(e.to()).gender();
      if (!opposite) {
        assertThat(e.attraction()).as(e.from() + "->" + e.to()).isZero();
        assertThat(e.kind()).isNotIn("PARTNER", "EX");
        assertThat(e.tags()).doesNotContain("crush");
      }
      assertThat(e.chemistry()).isBetween(-1.0, 1.0);
    }

    // temperament and prejudice counts from settings
    var presets = s.residents().stream().collect(Collectors.groupingBy(r -> r.temperament().preset(), Collectors.counting()));
    assertThat(presets.get(Temperament.GROUCH)).isEqualTo(3);
    assertThat(presets.get(Temperament.SWEETHEART)).isEqualTo(5);
    assertThat(s.residents().stream().filter(r -> r.biases().stream().anyMatch(WorldSeed.Bias::strong))).hasSize(4);

    // special ties
    long exPairs = s.relationships().stream().filter(e -> e.kind().equals("EX")).count() / 2;
    assertThat(exPairs).isEqualTo(2);
    assertThat(s.relationships().stream().filter(e -> e.tags().contains("feud"))).hasSize(2);
    assertThat(s.relationships().stream().filter(e -> e.tags().contains("crush")).count()).isBetween(2L, 3L);
    assertThat(s.relationships().stream().filter(e -> e.tags().contains("crush")))
        .allSatisfy(e -> assertThat(e.attraction()).as("a crush means attraction").isGreaterThanOrEqualTo(65));
    var ages = LIB.roster().residents().stream()
        .collect(Collectors.toMap(app.radiance.content.Roster.Resident::resolvedHandle, app.radiance.content.Roster.Resident::age));
    assertThat(s.relationships().stream().filter(e -> e.tags().contains("crush") || e.kind().equals("EX")))
        .allSatisfy(e -> assertThat(Math.abs(ages.get(e.from()) - ages.get(e.to()))).as("age gap").isLessThanOrEqualTo(15));
    var offNetworkFirstNames = s.residents().stream().flatMap(r -> r.offNetwork().stream())
        .map(p -> p.name().split(" ")[0]).toList();
    assertThat(offNetworkFirstNames).as("no two people in the world share a first name").doesNotHaveDuplicates();
    assertThat(offNetworkFirstNames).doesNotContainAnyElementsOf(
        s.residents().stream().map(r -> r.name().split(" ")[0]).toList());
    assertThat(s.relationships().stream().filter(e -> e.tags().stream().anyMatch(t -> t.startsWith("debate_rival"))).count())
        .isBetween(3L, 6L);
    var jessicaMegan = edge(s, "jessica.vogel", "megan.quinlan");
    assertThat(jessicaMegan.kind()).isEqualTo("CLOSE_FRIEND");
    assertThat(jessicaMegan.tags()).contains("best_friend");
    assertThat(edge(s, "megan.quinlan", "jessica.vogel").tags()).contains("best_friend", "suspects");
    assertThat(edge(s, "sarah.brennan", "tim.kaczmarek").tags()).contains("debate_rival:economics");
    assertThat(edge(s, "sarah.brennan", "ryan.kessler").kind()).isEqualTo("PARTNER");
    assertThat(edge(s, "sarah.brennan", "ryan.kessler").affinity()).as("showing cracks").isLessThan(50);

    // starting arcs: the pinned seeds plus others; at least 5 active
    assertThat(s.arcs()).hasSizeGreaterThanOrEqualTo(5);
    var sarah = arc(s, "romance", "sarah.brennan");
    assertThat(sarah.phase()).isEqualTo("official");
    assertThat(sarah.roles()).containsEntry("b", "ryan.kessler");
    var jessica = arc(s, "romance", "jessica.vogel");
    assertThat(jessica.phase()).isEqualTo("secret_dating");
    assertThat(jessica.visibility()).isEqualTo(Visibility.SECRET);
    String partner = jessica.roles().get("b");
    assertThat(byHandle.get(partner).gender()).isEqualTo(Gender.M);
    assertThat(byHandle.get(partner).status().name()).isEqualTo("SINGLE");
    assertThat(jessica.knowers()).as("the couple only; Megan isn't told").containsExactlyInAnyOrder("jessica.vogel", partner);
    assertThat(arc(s, "home_project", "samantha.dvorak").phase()).isEqualTo("progress");
    assertThat(arc(s, "car_saga", "jesse.hensley")).isNotNull();
    assertThat(s.arcs()).noneMatch(a -> LIB.isBlacklisted(a.defId()));
    assertThat(s.entities()).anyMatch(e -> e.key().equals("home:samantha.dvorak"))
        .anyMatch(e -> e.key().equals("car:jesse.hensley"));
  }

  @Test
  void offNetworkFamilyIsNamedByTheEngine() {
    var s = generate(7);
    var tim = resident(s, "tim.kaczmarek");
    assertThat(tim.offNetwork()).extracting(WorldSeed.OffNetworkPerson::relation).containsExactly("wife", "son")
        .doesNotContain("husband");
    assertThat(tim.offNetwork().get(0).name()).endsWith("Kaczmarek");
    assertThat(tim.offNetwork().stream().filter(p -> p.age() != null && p.relation().equals("son")))
        .allMatch(p -> p.age() <= 3 || p.relation().equals("daughter"));
    assertThat(resident(s, "amra.hodzic").offNetwork()).extracting(WorldSeed.OffNetworkPerson::relation)
        .containsExactly("husband", "son");
    assertThat(resident(s, "adam.rosen").offNetwork().stream().filter(p -> !p.relation().equals("wife")))
        .hasSize(2).extracting(WorldSeed.OffNetworkPerson::age).containsOnly(
            resident(s, "adam.rosen").offNetwork().get(1).age());
    assertThat(resident(s, "donna.novotny").offNetwork()).extracting(WorldSeed.OffNetworkPerson::relation)
        .containsExactly("late husband");
    assertThat(resident(s, "sarah.brennan").offNetwork()).isEmpty();
  }

  @Test
  void observantResidentsCareAboutFaithAndPinnedRivalsArgueEconomicsFromOppositeSides() {
    var s = generate(7);
    for (var h : List.of("brian.whitlock", "amra.hodzic", "nick.grabowski", "eleni.galanis", "kendra.gaines")) {
      assertThat(resident(s, h).interests()).containsKey("faith");
    }
    var sarah = resident(s, "sarah.brennan").politics();
    var tim = resident(s, "tim.kaczmarek").politics();
    assertThat(Math.signum(sarah.economic())).isEqualTo(-Math.signum(tim.economic()));
    assertThat(sarah.interest()).isGreaterThanOrEqualTo(0.8);
    var econ = s.residents().stream().mapToDouble(r -> Math.signum(r.politics().economic())).sum();
    assertThat(Math.abs(econ)).as("balanced across the spectrum").isLessThanOrEqualTo(4);
  }

  static WorldSeed.Relationship edge(WorldSeed s, String from, String to) {
    return s.relationships().stream().filter(e -> e.from().equals(from) && e.to().equals(to)).findFirst().orElseThrow();
  }

  static WorldSeed.Arc arc(WorldSeed s, String defId, String handle) {
    return s.arcs().stream().filter(a -> a.defId().equals(defId) && a.roles().containsValue(handle)).findFirst().orElse(null);
  }

  static WorldSeed.Resident resident(WorldSeed s, String handle) {
    return s.residents().stream().filter(r -> r.handle().equals(handle)).findFirst().orElseThrow();
  }

  @SuppressWarnings("unused")
  private static Map<String, Long> unused = new HashMap<>();
}
