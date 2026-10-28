package app.radiance.sim.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.content.Rules.Deltas;
import app.radiance.content.Vocab.Temperament;
import app.radiance.graph.RelationshipRow.Kind;
import app.radiance.kernel.RandomSource;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The pure math under the world model: OU drift, temperament, relaxation (SPEC §5.2, §5.3, §10). */
class MathTests {

  static final ContentLibrary LIB = ClasspathContent.load(List.<ContentProperties.BlacklistEntry>of());
  static final Person.Temper TYPICAL = new Person.Temper(Temperament.TYPICAL, 10, 1.0, 0.4, -30, 100);
  static final Person.Temper GROUCH = new Person.Temper(Temperament.GROUCH, -35, 0.5, 1.0, -100, 20);
  static final Person.Temper SWEET = new Person.Temper(Temperament.SWEETHEART, 30, 1.2, 0.2, 0, 100);

  @Test
  void oneBigOuStepIsDistributedLikeManySmallOnes() {
    double th = Drift.theta(36), sigma = Drift.sigma(0.25, th), mu = 0.1, x0 = -0.8;
    var rng = RandomSource.seeded(42);
    int n = 20_000;
    double[] big = new double[n], small = new double[n];
    for (int i = 0; i < n; i++) {
      big[i] = Drift.step(x0, mu, th, sigma, 72, rng.nextGaussian());
      double x = x0;
      for (int h = 0; h < 72; h++) x = Drift.step(x, mu, th, sigma, 1, rng.nextGaussian());
      small[i] = x;
    }
    assertThat(mean(big)).isCloseTo(mean(small), within(0.01));
    assertThat(sd(big)).isCloseTo(sd(small), within(0.01));
    // and both match the closed form
    double decay = Math.exp(-th * 72);
    assertThat(mean(big)).isCloseTo(mu + (x0 - mu) * decay, within(0.01));
    assertThat(sd(big)).isCloseTo(0.25 * Math.sqrt(1 - decay * decay), within(0.01));
  }

  @Test
  void temperamentShapesHowInteractionsLand() {
    var rules = LIB.temperament();
    assertThat(Meters.affinity(0, -10, TYPICAL, 0, rules, false)).isEqualTo(-4.0);
    assertThat(Meters.affinity(0, 10, TYPICAL, 0, rules, false)).isEqualTo(10.0);
    assertThat(Meters.affinity(0, -10, SWEET, 0.5, rules, false)).isEqualTo(0.0); // at the floor already
    assertThat(Meters.affinity(50, -10, SWEET, 0, rules, false)).isEqualTo(48.0);
    // a bad mood makes bad land harder and good softer
    assertThat(Meters.affinity(0, -10, TYPICAL, -1, rules, false)).isEqualTo(-6.0);
    assertThat(Meters.affinity(0, 10, TYPICAL, -1, rules, false)).isEqualTo(5.0);
  }

  @Test
  void meterChangesTaperNearBoundsAndOnlyArcEventsCrossThem() {
    var rules = LIB.temperament();
    // a grouch's ceiling is 20: 5 points short, a +10 lands as +10·(5/15)
    assertThat(Meters.affinity(15, 10, GROUCH, 0, rules, false)).isCloseTo(15 + 5 * (5 / 15.0), within(1e-9));
    double a = 0;
    for (int i = 0; i < 200; i++) a = Meters.affinity(a, 10, GROUCH, 0, rules, false);
    assertThat(a).isLessThanOrEqualTo(20);
    double t = 0;
    for (int i = 0; i < 200; i++) t = Meters.affinity(t, -10, TYPICAL, -1, rules, false);
    assertThat(t).isGreaterThanOrEqualTo(-30);
    assertThat(Meters.affinity(-28, -50, TYPICAL, 0, rules, true)).isEqualTo(-48.0);
  }

  @Test
  void relaxationComposesExactly() {
    var rels = LIB.relationships();
    var grouch = person("g", GROUCH);
    var once = edge("g", "x", Kind.FRIEND, 60, 80);
    var twice = edge("g", "x", Kind.FRIEND, 60, 80);
    Meters.settle(once, grouch, rels, 21, 30 * 86_400_000L);
    Meters.settle(twice, grouch, rels, 21, 9 * 86_400_000L);
    Meters.settle(twice, grouch, rels, 21, 30 * 86_400_000L);
    assertThat(twice.affinity).isCloseTo(once.affinity, within(1e-9));
    assertThat(twice.familiarity).isCloseTo(once.familiarity, within(1e-9));
    // a grouch's friend meter relaxes toward 25 − 35 = −10
    Meters.settle(once, grouch, rels, 21, 400 * 86_400_000L);
    assertThat(once.affinity).isCloseTo(-10, within(0.1));
  }

  @Test
  void deltasMoveTheReceiversViewThroughTheirTemperament() {
    var e = edge("s", "x", Kind.FRIEND, 40, 50);
    Meters.apply(e, new Deltas(-9, -4, 0, 0, -4), person("s", SWEET), LIB.temperament(), false, 5);
    assertThat(e.affinity).isCloseTo(40 - 9 * 0.2, within(1e-9));
    assertThat(e.trust).isEqualTo(46.0);
    assertThat(e.lastInteraction).isEqualTo(5L);
    assertThat(e.dirty).isTrue();
  }

  static Person person(String id, Person.Temper t) {
    var p = new Person(id, id, id, false, app.radiance.content.Vocab.Gender.F, "1990-01-01", List.of(),
        java.util.Map.of(), java.util.Set.of(), java.util.Map.of(), new Person.Politics(0, 0, 0), List.of(),
        java.util.Map.of(), t, null, null, app.radiance.content.Vocab.Observance.NONE, false, false);
    p.traitOffsets = new java.util.HashMap<>();
    return p;
  }

  static Edge edge(String from, String to, Kind kind, double affinity, double familiarity) {
    return new Edge(from, to, kind, affinity, 50, 0, familiarity, 0, 0, new TreeSet<>(), new TreeMap<>(), true, null,
        null, 0);
  }

  static double mean(double[] xs) {
    double s = 0;
    for (double x : xs) s += x;
    return s / xs.length;
  }

  static double sd(double[] xs) {
    double m = mean(xs), s = 0;
    for (double x : xs) s += (x - m) * (x - m);
    return Math.sqrt(s / xs.length);
  }
}
