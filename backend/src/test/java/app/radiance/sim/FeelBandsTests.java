package app.radiance.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The feel bands CI asserts at default knobs (SPEC §10), on 30 simulated days of seed 42. Bands that
 * need the Pacer are M7's.
 */
class FeelBandsTests {

  @TempDir Path dir;

  @Test
  void aMonthWithAWarmRStaysInEveryBand() {
    try (var sim = new Simulation(Simulation.Options.of(42, 30, Persona.WARM, dir.resolve("warm"))).run()) {
      var report = FeelReport.of(sim);
      assertThat(report.bands).as(report.render(sim)).allMatch(FeelReport.Band::ok);
      assertThat(sim.spoilers).isEmpty();
    }
  }

  @Test
  void aPretentiousRCollectsFinds_pretentiousAndFewerCommentsThanAWarmOne() {
    try (var warm = new Simulation(Simulation.Options.of(42, 30, Persona.WARM, dir.resolve("w"))).run();
        var pretentious = new Simulation(Simulation.Options.of(42, 30, Persona.PRETENTIOUS, dir.resolve("p"))).run()) {
      var w = FeelReport.of(warm);
      var p = FeelReport.of(pretentious);
      assertThat(p.userTags.getOrDefault("finds_pretentious", 0)).isGreaterThan(w.userTags.getOrDefault("finds_pretentious", 0));
      assertThat(p.userPostComments).isLessThan(w.userPostComments);
    }
  }
}
