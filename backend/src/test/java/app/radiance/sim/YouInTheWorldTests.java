package app.radiance.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SPEC §12 M11's AC, with scripted R personas over a simulated month. */
class YouInTheWorldTests {

  @TempDir Path dir;

  @Test
  void aCombativeRStaysInTheTemperamentBands() {
    try (var sim = new Simulation(Simulation.Options.of(42, 30, Persona.COMBATIVE, dir.resolve("combative"))).run()) {
      var report = FeelReport.of(sim);
      assertThat(report.bands).as(report.render(sim))
          .filteredOn(b -> b.name().startsWith("no sweetheart") || b.name().startsWith("typical residents")
              || b.name().startsWith("each grouch") || b.name().startsWith("nobody with faithfulness"))
          .hasSizeGreaterThanOrEqualTo(4).allMatch(FeelReport.Band::ok);
    }
  }

  /** Flirting back from some attached residents; the most faithful shut it down. */
  @Test
  void aFlirtyRGetsFlirtingBackFromSomeAttachedResidentsAndAShutdownFromTheMostFaithful() {
    try (var sim = new Simulation(Simulation.Options.of(42, 60, Persona.FLIRTY, dir.resolve("flirty"))).run()) {
      var w = sim.engine.world();
      var rows = sim.sql.query("SELECT c.author_id, c.brief_json FROM comment c JOIN comment mine ON mine.id = c.parent_id"
          + " WHERE mine.author_id = ? AND mine.brief_json LIKE '%\"FLIRT\"%' AND c.air_status <> 'DROPPED'", List.of(w.userId),
          r -> new String[] {r.str("author_id"), r.str("brief_json")});
      var attachedFlirtingBack = new ArrayList<String>();
      var faithfulAnswers = new HashMap<String, Integer>();
      int faithfulFlirts = 0;
      for (var row : rows) {
        var p = w.person(row[0]);
        if (p == null || p.user || !p.attached()) continue;
        boolean flirt = "FLIRT".equals(Brief.parse(row[1]).stance());
        if (p.trait("faithfulness") >= 80) {
          faithfulAnswers.merge(p.id, 1, Integer::sum);
          if (flirt) faithfulFlirts++;
        } else if (flirt) {
          attachedFlirtingBack.add(p.id);
        }
      }
      assertThat(attachedFlirtingBack).as("attached residents who flirted back, of %d answers", rows.size()).isNotEmpty();
      assertThat(faithfulFlirts).as("the most faithful never play along").isZero();
      assertThat(faithfulAnswers).as("the most faithful answered at all").isNotEmpty();
    }
  }
}
