package app.radiance.narrative;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.kernel.Json;
import app.radiance.llm.PromptBudget;
import app.radiance.sim.Persona;
import app.radiance.sim.Simulation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Context packets (SPEC §5.10, §10 prompt tests): stable prefix, every brief once, under budget. */
class ContextPacketTests {

  static final PromptBudget BUDGET = new PromptBudget(24_000, 4.0);

  @TempDir Path dir;

  @Test
  void packetsCarryEveryBriefUnderBudgetWithAPrefixThatNeverChanges() {
    try (var sim = new Simulation(Simulation.Options.of(7, 2, Persona.WARM, dir)).run()) {
      var world = sim.engine.world();
      var builder = new ContextPacketBuilder(sim.sql, sim.lib, "Radiance", world);
      var threads = sim.sql.query("SELECT id FROM post WHERE body IS NULL AND air_status <> 'DROPPED'"
          + " ORDER BY created_at LIMIT 40", List.of(), r -> r.str("id"));
      assertThat(threads).hasSizeGreaterThan(10);
      var prefix = builder.prefix();
      String joined = prefix.getFirst().text() + prefix.getLast().text();
      assertThat(joined).contains("# World bible", "# Cast directory", "@jessica.vogel").doesNotContain("{{");
      assertThat(joined).doesNotContainPattern("20\\d\\d-\\d\\d-\\d\\d"); // nothing date-specific
      int withComments = 0;
      for (String id : threads) {
        var packet = builder.thread(id, BUDGET);
        assertThat(packet.prefix()).isEqualTo(prefix);
        assertThat(packet.content()).contains("<today>", "<people>", "<canon>", "<thread>", "<briefs>");
        assertThat(BUDGET.estimateTokens(packet.content()) + BUDGET.estimateTokens(joined)).isLessThan(24_000);
        int from = packet.content().indexOf("<briefs>"), to = packet.content().indexOf("</briefs>");
        var briefs = Json.tree(packet.content().substring(from + 8, to).strip());
        assertThat(briefs.size()).isEqualTo(packet.targets().size());
        assertThat(packet.targets()).containsKey(id);
        var ids = new java.util.HashSet<String>();
        briefs.forEach(b -> assertThat(ids.add(b.path("briefId").asString())).isTrue());
        if (packet.targets().size() > 1) withComments++;
        var rendered = FakeRender.render(packet.content());
        assertThat(rendered.items()).hasSize(packet.targets().size());
      }
      assertThat(withComments).isPositive();
      // the prefix is the same on a later world-day, from a new builder
      var later = new ContextPacketBuilder(sim.sql, sim.lib, "Radiance", world);
      assertThat(later.prefix()).isEqualTo(prefix);
    }
  }

  @Test
  void nonKnowersAreToldNotToHintAtASecretAndKnowersSeeIt() {
    try (var sim = new Simulation(Simulation.Options.of(7, 1, Persona.WARM, dir)).run()) {
      var w = sim.engine.world();
      var builder = new ContextPacketBuilder(sim.sql, sim.lib, "Radiance", w);
      var h = w.byHandle();
      var jessica = h.get("jessica.vogel");
      var nick = h.get("nick.grabowski");
      var megan = h.get("megan.quinlan");
      var involved = new java.util.LinkedHashSet<>(List.of(jessica.id, megan.id));
      if (w.arcs.values().stream().anyMatch(a -> a.active() && a.defId.equals("romance") && a.involves(nick.id)
          && a.visibility != app.radiance.kernel.Visibility.PUBLIC)) {
        assertThat(builder.mustNotReveal(megan, involved)).anySatisfy(l -> assertThat(l).contains("Jessica", "Nick"));
        assertThat(builder.mustNotReveal(jessica, involved)).isEmpty();
        assertThat(builder.people(involved, w.time, BUDGET)).contains("known only to");
      }
    }
  }
}
