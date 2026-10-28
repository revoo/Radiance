package app.radiance.sim;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.kernel.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

/**
 * Property tests (SPEC §10): random seeds, 30 simulated days of nightly scripts with scripted R sessions
 * in between. The auditor runs in THROW mode, so any §5.5 invariant a plan breaks fails the run.
 */
class SimulationPropertyTests {

  @Property(tries = 2)
  void everyInvariantHoldsForAMonth(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) throws Exception {
    var dir = Files.createTempDirectory("radiance-prop-");
    try (var sim = new Simulation(Simulation.Options.of(seed, 30, Persona.COMBATIVE, dir)).run()) {
      var w = sim.engine.world();
      // 9: no spoilers, at every advance
      assertThat(sim.spoilers).isEmpty();
      // 1–7, 10, 11: the auditor would have thrown; nothing was recorded either
      assertThat(sim.days).allMatch(d -> d.findings().isEmpty());
      // 10: no blacklisted arc ever, no blacklisted theme in any brief
      assertThat(w.arcs.values()).noneMatch(a -> sim.lib.isBlacklisted(a.defId));
      var briefs = sim.sql.query("SELECT brief_json AS b FROM post UNION ALL SELECT brief_json FROM comment", List.of(),
          r -> r.str("b"));
      var linter = sim.lib.themeLinter();
      assertThat(briefs).allMatch(b -> b == null || linter.hits(b).isEmpty());
      // attention: no resident spends more than their budget in a world-day
      var spent = sim.sql.query("SELECT author_id AS a, created_at AS t FROM comment WHERE air_status = 'AIRED'"
          + " UNION ALL SELECT actor_id, created_at FROM reaction WHERE air_status = 'AIRED'", List.of(),
          r -> r.str("a") + "|" + w.day(r.lng("t")));
      var counts = new java.util.HashMap<String, Integer>();
      spent.forEach(k -> counts.merge(k, 1, Integer::sum));
      for (var e : counts.entrySet()) {
        var p = w.person(e.getKey().substring(0, e.getKey().indexOf('|')));
        if (p.user) continue;
        // answering R is always allowed (SPEC §5.8), so allow a little headroom for those
        assertThat(e.getValue()).as("@" + p.handle + " on " + e.getKey()).isLessThanOrEqualTo(p.routine.attentionBudget() + 6);
      }
      // caps: posts per resident per day, drama per day
      var posts = new java.util.HashMap<String, Integer>();
      sim.sql.query("SELECT author_id AS a, created_at AS t FROM post WHERE air_status = 'AIRED' AND origin <> 'USER'",
          List.of(), r -> r.str("a") + "|" + w.day(r.lng("t"))).forEach(k -> posts.merge(k, 1, Integer::sum));
      assertThat(posts.values()).allMatch(n -> n <= Planner.MAX_POSTS_PER_DAY);
      assertThat(w.dramaByDay.values()).allMatch(n -> n <= sim.options.knobs().maxDramaPerDay());
      // 11: every romantic edge pairs a man and a woman
      assertThat(w.edges.values()).noneMatch(e -> e.attraction > 0 && w.people.containsKey(e.from)
          && w.people.containsKey(e.to) && w.person(e.from).gender == w.person(e.to).gender);
    }
  }

  @Test
  void theSameSeedGivesTheSamePlanHashes() throws Exception {
    List<String> a, b;
    try (var sim = new Simulation(Simulation.Options.of(7, 4, Persona.WARM, Files.createTempDirectory("radiance-a-"))).run()) {
      a = sim.days.stream().map(Simulation.Day::planHash).toList();
    }
    try (var sim = new Simulation(Simulation.Options.of(7, 4, Persona.WARM, Files.createTempDirectory("radiance-b-"))).run()) {
      b = sim.days.stream().map(Simulation.Day::planHash).toList();
    }
    assertThat(a).isEqualTo(b).hasSize(4).doesNotHaveDuplicates();
    assertThat(Json.write(a)).isNotBlank();
  }

  @Test
  void impressionTagsWaitOutTheirHysteresis() {
    var e = new app.radiance.sim.model.Edge("v", "r", app.radiance.graph.RelationshipRow.Kind.FRIEND, 80, 70, 0, 60, 0, 0,
        new java.util.TreeSet<>(), new java.util.TreeMap<>(), true, null, null, 0);
    Impressions.retag(e, null, 0);
    assertThat(e.tags).doesNotContain("fan");
    Impressions.retag(e, null, Impressions.HYSTERESIS - 1);
    assertThat(e.tags).doesNotContain("fan");
    Impressions.retag(e, null, Impressions.HYSTERESIS);
    assertThat(e.tags).contains("fan");
    // a dip shorter than two days doesn't flip it back
    e.affinity = 20;
    Impressions.retag(e, null, Impressions.HYSTERESIS + 1000);
    e.affinity = 80;
    Impressions.retag(e, null, Impressions.HYSTERESIS + 86_400_000L);
    e.affinity = 20;
    Impressions.retag(e, null, 2 * Impressions.HYSTERESIS);
    assertThat(e.tags).contains("fan");
  }

  static Path tmp(String p) throws Exception {
    return Files.createTempDirectory(p);
  }
}
