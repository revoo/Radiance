package app.radiance.narrative;

import app.radiance.jobs.JobKinds;
import app.radiance.jobs.JobRunner;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.OfflineGateway;
import app.radiance.llm.Priority;
import app.radiance.sim.Simulation;
import java.util.ArrayList;
import java.util.List;

/**
 * A simulation whose nightly scripts are rendered (SPEC §5.10 script path): after each batch the
 * RENDER_THREAD and RENDER_DM jobs run through the {@link JobRunner}, and a {@link ProjectedMeter}
 * prices what a real model would have cost. Offline by default (FakeLlm); pass any gateway to render
 * for real.
 */
public final class RenderedRun {

  /** One night's render, as the meter saw it. */
  public record Night(java.time.LocalDate date, JobRunner.Report jobs, double usd, int calls, double hitRate) {}

  private final ProjectedMeter meter;
  private Renderer renderer;
  public final List<Night> nights = new ArrayList<>();
  public final List<Renderer.Result> results = java.util.Collections.synchronizedList(new ArrayList<>());

  public RenderedRun(LlmGateway gateway) {
    this.meter = new ProjectedMeter(gateway, ProjectedMeter.Pricing.HAIKU, 4.0);
  }

  /** FakeLlm with the offline render templates. */
  public static RenderedRun offline() {
    var fakes = new FakeResponses();
    FakeRender.register(fakes);
    return new RenderedRun(OfflineGateway.create(fakes, app.radiance.kernel.WallClock.system()));
  }

  public ProjectedMeter meter() {
    return meter;
  }

  /** Runs {@code options} with rendering after every batch. */
  public Simulation run(Simulation.Options options) {
    return new Simulation(options.rendering(this::renderNight)).run();
  }

  void renderNight(Simulation sim) {
    if (renderer == null) {
      renderer = new Renderer(sim.sql, sim.sqlLane(), sim.lib, meter, sim.engine.world(), "Radiance", true, Priority.SCRIPT);
    }
    double before = meter.usd();
    int callsBefore = meter.totals().values().stream().mapToInt(t -> t.calls).sum();
    var report = new JobRunner(sim.sql, sim.sqlLane(), sim.clock(), 4)
        .on(JobKinds.RENDER_THREAD, job -> results.add(renderer.renderThread(job.refId())))
        .on(JobKinds.RENDER_DM, job -> results.add(renderer.renderDms(job.refId())))
        .drain();
    var t = meter.totals().get(app.radiance.llm.Purpose.RENDER_THREAD);
    int calls = meter.totals().values().stream().mapToInt(x -> x.calls).sum() - callsBefore;
    var date = sim.days.isEmpty() ? null : sim.days.getLast().date();
    nights.add(new Night(date, report, meter.usd() - before, calls, t == null ? 0 : t.hitRate()));
  }
}
