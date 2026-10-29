package app.radiance.narrative;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.llm.Purpose;
import app.radiance.sim.Persona;
import app.radiance.sim.Simulation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The nightly script, rendered (SPEC §12 M5 AC, offline): every aired post and comment has prose, a
 * FULL day projects under $0.15 at the default haiku prices, and the cached prefix never changes.
 */
class RenderedRunTests {

  @TempDir Path dir;

  @Test
  void aFullDayRendersUnderBudgetWithOnePrefix() {
    var run = RenderedRun.offline();
    try (var sim = run.run(Simulation.Options.of(7, 2, Persona.WARM, dir))) {
      long unwritten = sim.sql.queryOne("SELECT COUNT(*) AS n FROM post WHERE air_status = 'AIRED' AND body IS NULL",
          List.of(), r -> r.lng("n")).orElseThrow();
      long comments = sim.sql.queryOne("SELECT COUNT(*) AS n FROM comment WHERE air_status = 'AIRED' AND body IS NULL"
          + " AND origin = 'SCRIPT'", List.of(), r -> r.lng("n")).orElseThrow();
      assertThat(unwritten).isZero();
      assertThat(comments).isZero();
      assertThat(run.nights).hasSize(2).allSatisfy(n -> {
        assertThat(n.usd()).as("projected cost of a FULL night").isLessThanOrEqualTo(0.15);
        assertThat(n.jobs().failed()).isZero();
      });
      var t = run.meter().totals().get(Purpose.RENDER_THREAD);
      assertThat(t.prefixes).hasSize(1);
      assertThat(t.hitRate()).isGreaterThanOrEqualTo(0.8);
      assertThat(sim.sql.queryOne("SELECT COUNT(*) AS n FROM event WHERE recap_line IS NOT NULL", List.of(), r -> r.lng("n"))
          .orElseThrow()).isGreaterThanOrEqualTo(0);
    }
  }
}
