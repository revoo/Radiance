package app.radiance.narrative;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.llm.LlmGateway;
import app.radiance.llm.LlmRequest;
import app.radiance.llm.LlmResult;
import app.radiance.llm.Outcome;
import app.radiance.llm.Part;
import app.radiance.llm.PromptBudget;
import app.radiance.llm.Purpose;
import app.radiance.llm.Usage;
import app.radiance.llm.Priority;
import app.radiance.sim.Persona;
import app.radiance.sim.Simulation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The render path and its validation (SPEC §5.10), against stub models that misbehave on purpose. */
class RendererTests {

  @TempDir Path dir;

  /** A gateway whose render and judge answers come from functions of the request's text. */
  static final class StubGateway implements LlmGateway {
    final Function<String, RenderBatch> render;
    final Function<String, Boolean> judge;
    Outcome renderOutcome = Outcome.OK;
    int degradation;
    final List<String> renderCalls = new ArrayList<>();

    StubGateway(Function<String, RenderBatch> render, Function<String, Boolean> judge) {
      this.render = render;
      this.judge = judge;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> LlmResult<T> call(LlmRequest<T> r) {
      String text = r.content().stream().map(p -> p instanceof Part.Text t ? t.text() : "").reduce("", String::concat);
      Object value;
      if (r.output() == JudgeAnswer.class) {
        value = new JudgeAnswer(judge.apply(text));
      } else {
        renderCalls.add(text);
        if (renderOutcome != Outcome.OK) return new LlmResult<>(renderOutcome, null, Usage.ZERO, Duration.ZERO, "x");
        value = render.apply(text);
      }
      return new LlmResult<>(Outcome.OK, (T) value, Usage.ZERO, Duration.ZERO, "x");
    }

    @Override
    public <T> List<LlmResult<T>> callAll(List<LlmRequest<T>> requests) {
      return requests.stream().map(this::call).toList();
    }

    @Override
    public PromptBudget budgetFor(Purpose purpose) {
      return new PromptBudget(24_000, 4.0);
    }

    @Override
    public int degradationLevel() {
      return degradation;
    }
  }

  static List<String> threads(Simulation sim) {
    return sim.sql.query("SELECT id FROM post WHERE body IS NULL AND air_status <> 'DROPPED' ORDER BY created_at",
        List.of(), r -> r.str("id"));
  }

  @Test
  void anHonestOfflineRenderWritesEveryItem() {
    try (var sim = new Simulation(Simulation.Options.of(7, 1, Persona.WARM, dir)).run()) {
      var gateway = new StubGateway(FakeRender::render, text -> false);
      var renderer = new Renderer(sim.sql, sim.sqlLane(), sim.lib, gateway, sim.engine.world(), "Radiance", true,
          Priority.SCRIPT);
      for (String id : threads(sim)) renderer.renderThread(id);
      long unwritten = sim.sql.queryOne("SELECT COUNT(*) AS n FROM post WHERE body IS NULL AND air_status <> 'DROPPED'",
          List.of(), r -> r.lng("n")).orElseThrow();
      long comments = sim.sql.queryOne("SELECT COUNT(*) AS n FROM comment WHERE body IS NULL AND render_status = 'PLANNED'"
          + " AND air_status <> 'DROPPED'", List.of(), r -> r.lng("n")).orElseThrow();
      assertThat(unwritten).isZero();
      assertThat(comments).isZero();
      assertThat(sim.sql.queryOne("SELECT COUNT(*) AS n FROM comment WHERE gist IS NOT NULL", List.of(), r -> r.lng("n"))
          .orElseThrow()).isPositive();
    }
  }

  @Test
  void aModelThatBreaksTheRulesIsFixedOnceThenFallsBack() {
    try (var sim = new Simulation(Simulation.Options.of(7, 1, Persona.WARM, dir)).run()) {
      // first attempt: every item is bad; second attempt: comments still bad, posts fixed
      Function<String, RenderBatch> bad = text -> {
        var honest = FakeRender.render(text);
        boolean retry = text.contains("<fix>");
        var items = new ArrayList<RenderBatch.Item>();
        for (var i : honest.items()) {
          boolean comment = !i.text().isEmpty() && FakeRender.LINES.containsValue(i.text());
          String t = retry && !comment ? i.text() : i.text() + " — we should all delve into this, she's pregnant";
          var facts = retry ? List.<RenderBatch.Fact>of() : List.of(new RenderBatch.Fact("@jake.zielinski", "quit his job", true));
          items.add(new RenderBatch.Item(i.briefId(), t, i.gist(), i.recapLine(), facts));
        }
        return new RenderBatch(items);
      };
      var gateway = new StubGateway(bad, text -> text.contains("pregnant"));
      var renderer = new Renderer(sim.sql, sim.sqlLane(), sim.lib, gateway, sim.engine.world(), "Radiance", true,
          Priority.SCRIPT);
      int fellBack = 0, rerendered = 0;
      for (String id : threads(sim)) {
        var r = renderer.renderThread(id);
        fellBack += r.fellBack();
        rerendered += r.rerendered();
      }
      assertThat(rerendered).isPositive();
      assertThat(fellBack).isPositive();
      // nothing that was written mentions the blacklisted theme or an LLM-ism
      var bodies = sim.sql.query("SELECT body FROM post WHERE body IS NOT NULL UNION ALL SELECT body FROM comment"
          + " WHERE body IS NOT NULL", List.of(), r -> r.str("body"));
      assertThat(bodies).isNotEmpty().noneMatch(b -> b.contains("pregnant") || b.contains("delve"));
      // a bad comment became its author's reaction to the post
      assertThat(sim.sql.queryOne("SELECT COUNT(*) AS n FROM comment WHERE render_status = 'DROPPED'", List.of(),
          r -> r.lng("n")).orElseThrow()).isPositive();
      assertThat(gateway.renderCalls.stream().filter(c -> c.contains("don't introduce major life facts"))).isNotEmpty();
      assertThat(sim.sql.queryOne("SELECT COUNT(*) AS n FROM fact WHERE source = 'PROSE'", List.of(), r -> r.lng("n"))
          .orElseThrow()).isZero();
    }
  }

  @Test
  void whenEveryModelRefusesTheResidentsJustReact() {
    try (var sim = new Simulation(Simulation.Options.of(7, 1, Persona.WARM, dir)).run()) {
      var gateway = new StubGateway(FakeRender::render, text -> false);
      gateway.renderOutcome = Outcome.REFUSED;
      var renderer = new Renderer(sim.sql, sim.sqlLane(), sim.lib, gateway, sim.engine.world(), "Radiance", true,
          Priority.SCRIPT);
      String thread = sim.sql.queryOne("SELECT p.id FROM post p WHERE p.body IS NULL AND EXISTS (SELECT 1 FROM comment c"
          + " WHERE c.post_id = p.id AND c.body IS NULL) LIMIT 1", List.of(), r -> r.str("id")).orElseThrow();
      var r = renderer.renderThread(thread);
      assertThat(r.fellBack()).isPositive();
      assertThat(r.rendered()).isZero();
      assertThat(sim.sql.queryOne("SELECT render_status FROM post WHERE id = ?", List.of(thread), x -> x.str("render_status"))
          .orElseThrow()).isEqualTo("DROPPED");
    }
  }

  @Test
  void minorFactsArePendingUntilTheirItemAirs() {
    try (var sim = new Simulation(Simulation.Options.of(7, 1, Persona.WARM, dir)).run()) {
      Function<String, RenderBatch> facts = text -> {
        var honest = FakeRender.render(text);
        var items = honest.items().stream().map(i -> new RenderBatch.Item(i.briefId(), i.text(), i.gist(), i.recapLine(),
            List.of(new RenderBatch.Fact("@jesse.hensley", "bought new floor mats", false)))).toList();
        return new RenderBatch(items);
      };
      var renderer = new Renderer(sim.sql, sim.sqlLane(), sim.lib, new StubGateway(facts, t -> false), sim.engine.world(),
          "Radiance", true, Priority.SCRIPT);
      // the script past the end of the run is still unaired
      String future = sim.sql.queryOne("SELECT id FROM post WHERE air_status = 'SCRIPTED' AND body IS NULL ORDER BY"
          + " created_at LIMIT 1", List.of(), r -> r.str("id")).orElseThrow();
      long at = sim.sql.queryOne("SELECT created_at FROM post WHERE id = ?", List.of(future), r -> r.lng("created_at"))
          .orElseThrow();
      renderer.renderThread(future);
      assertThat(status(sim, future)).isEqualTo("PENDING");
      sim.engine.advanceTo(at + 1);
      assertThat(status(sim, future)).isIn("ACTIVE", "VOID");
    }
  }

  @Test
  void theDegradationLadderTrimsCommentsToReactions() {
    try (var sim = new Simulation(Simulation.Options.of(7, 1, Persona.WARM, dir)).run()) {
      var gateway = new StubGateway(FakeRender::render, t -> false);
      gateway.degradation = 4;
      var renderer = new Renderer(sim.sql, sim.sqlLane(), sim.lib, gateway, sim.engine.world(), "Radiance", true,
          Priority.SCRIPT);
      for (String id : threads(sim)) renderer.renderThread(id);
      assertThat(sim.sql.queryOne("SELECT COUNT(*) AS n FROM comment WHERE render_status = 'RENDERED' AND origin = 'SCRIPT'",
          List.of(), r -> r.lng("n")).orElseThrow()).isZero();
    }
  }

  static String status(Simulation sim, String origin) {
    return sim.sql.queryOne("SELECT status FROM fact WHERE origin_ref = ? LIMIT 1", List.of(origin), r -> r.str("status"))
        .orElse("none");
  }
}
