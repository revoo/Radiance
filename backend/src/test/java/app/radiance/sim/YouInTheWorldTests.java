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

  /** SPEC §5.9: engage with someone's posts and they're likelier to engage with yours. */
  @Test
  void aResidentIsLikelierToEngageWithSomeoneWhoRecentlyEngagedWithThem() {
    try (var sim = new Simulation(Simulation.Options.of(42, 2, Persona.WARM, dir.resolve("reciprocity"))).run()) {
      var w = sim.engine.world();
      var user = w.person(w.userId);
      var planner = new Planner(sim.lib, Knobs.defaults());
      var resident = w.residents().getFirst();
      var post = w.items.values().stream().filter(i -> i.type == app.radiance.sim.model.Item.Type.POST && w.userId.equals(i.authorId))
          .findFirst().orElseThrow();
      var back = w.edge(w.userId, resident.id);
      String day = w.day(w.time);

      back.lastInteraction = null;
      double strangers = planner.utility(w, resident, post, user, day, app.radiance.kernel.RandomSource.seeded(1));
      back.lastInteraction = w.time - 86_400_000L;
      double recent = planner.utility(w, resident, post, user, day, app.radiance.kernel.RandomSource.seeded(1));
      back.lastInteraction = w.time - 10 * 86_400_000L;
      double longAgo = planner.utility(w, resident, post, user, day, app.radiance.kernel.RandomSource.seeded(1));

      assertThat(recent).isGreaterThan(strangers);
      assertThat(longAgo).as("it wears off after a week").isEqualTo(strangers);
    }
  }

  /** Reciprocity needs R's own actions to count: reacting and commenting stamp his edge toward the author. */
  @Test
  void whatRDoesOnAPostIsWhatReciprocityReads() {
    try (var sim = new Simulation(Simulation.Options.of(42, 3, Persona.WARM, dir.resolve("stamp"))).run()) {
      var w = sim.engine.world();
      long stamped = w.residents().stream().filter(b -> {
        var e = w.edgeOrNull(w.userId, b.id);
        return e != null && e.lastInteraction != null;
      }).count();
      assertThat(sim.userComments).isNotEmpty();
      assertThat(stamped).as("residents R has engaged with").isPositive();
    }
  }
}
