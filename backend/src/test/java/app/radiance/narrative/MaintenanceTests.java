package app.radiance.narrative;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.jobs.JobKinds;
import app.radiance.jobs.JobRepository;
import app.radiance.jobs.JobRow;
import app.radiance.jobs.JobRunner;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.OfflineGateway;
import app.radiance.memory.MemoryRepository;
import app.radiance.memory.MemoryRow;
import app.radiance.persistence.UnitOfWork;
import app.radiance.sim.Persona;
import app.radiance.sim.Simulation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compaction and relationship summaries (SPEC §5.7, §5.3), as batch maintenance jobs. */
class MaintenanceTests {

  @TempDir Path dir;

  @Test
  void oldMemoriesCompactAndChangedEdgesGetNewSummaries() {
    try (var sim = new Simulation(Simulation.Options.of(7, 1, Persona.WARM, dir)).run()) {
      var w = sim.engine.world();
      var jake = w.byHandle().get("jake.zielinski");
      // one of Jake's seeded ties, so its row exists
      var tim = w.person(w.edgesFrom(jake.id).stream().filter(x -> !x.to.equals(w.userId)).findFirst().orElseThrow().to);
      // 50 old episodes for Jake
      var rows = new ArrayList<MemoryRow>();
      for (int i = 0; i < 50; i++) {
        rows.add(new MemoryRow("01OLD" + String.format("%021d", i), jake.id, w.time - 20 * 86_400_000L + i * 60_000L,
            MemoryRow.Kind.EPISODE, "Jake roasted Jesse's car #" + i, 3, "[\"" + tim.id + "\"]", "[]", null, null, false));
      }
      sim.sqlLane().run(() -> sim.sql.commit(new MemoryRepository(sim.sql).save(new UnitOfWork(), rows)));
      // a material change toward Tim
      var e = w.edge(jake.id, tim.id);
      e.familiarity = 60;
      e.pending.put("~sumaff", Math.round((e.affinity + 30) * 1000));

      var fakes = new FakeResponses();
      FakeRender.register(fakes);
      var gateway = OfflineGateway.create(fakes, sim.clock());
      var m = new Maintenance(sim.sql, sim.sqlLane(), sim.lib, gateway, w, "Radiance");
      assertThat(m.enqueue(sim.clock().nowMs())).isGreaterThanOrEqualTo(2);
      var jobs = new JobRepository(sim.sql);
      assertThat(jobs.active(JobKinds.REFLECT, jake.id)).isPresent();
      assertThat(jobs.active(JobKinds.SUMMARY, jake.id + "|" + tim.id)).isPresent();
      var report = new JobRunner(sim.sql, sim.sqlLane(), sim.clock(), 2)
          .on(JobKinds.REFLECT, j -> m.reflect(j.refId()))
          .on(JobKinds.SUMMARY, j -> m.summarize(j.refId()))
          .drain();
      assertThat(report.failed()).isZero();
      assertThat(jobs.count(JobRow.Status.DONE)).isGreaterThanOrEqualTo(2);
      long compacted = sim.sql.queryOne("SELECT COUNT(*) AS n FROM memory WHERE owner_id = ? AND compacted = 1",
          List.of(jake.id), r -> r.lng("n")).orElseThrow();
      var errors = sim.sql.query("SELECT kind, status, last_error FROM job WHERE kind IN ('REFLECT', 'SUMMARY')", List.of(),
          r -> r.str("kind") + " " + r.str("status") + " " + r.str("last_error"));
      assertThat(compacted).as(errors.toString()).isGreaterThanOrEqualTo(50);
      assertThat(sim.sql.queryOne("SELECT COUNT(*) AS n FROM memory WHERE owner_id = ? AND kind = 'PERIOD_SUMMARY'",
          List.of(jake.id), r -> r.lng("n")).orElseThrow()).isBetween(1L, 3L);
      assertThat(sim.sql.queryOne("SELECT life_summary FROM persona WHERE actor_id = ?", List.of(jake.id),
          r -> r.str("life_summary")).orElseThrow()).isNotBlank();
      assertThat(sim.sql.queryOne("SELECT summary FROM relationship WHERE from_id = ? AND to_id = ?",
          List.of(jake.id, tim.id), r -> r.str("summary")).orElseThrow()).isEqualTo("They get along fine.");
      // and now it's not due again
      assertThat(m.summaryDue(e)).isFalse();
    }
  }
}
