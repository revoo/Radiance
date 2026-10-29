package app.radiance.narrative;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.kernel.Json;
import app.radiance.sim.Persona;
import app.radiance.sim.Simulation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The bake-off harness (SPEC §5.10) with two offline "models": an honest one and a chatty one. */
class BakeoffTests {

  @TempDir Path dir;

  @Test
  void fixturesAreVariedAndTheReportAndBlindFileComeOut() throws Exception {
    List<Bakeoff.Fixture> fixtures;
    try (var sim = new Simulation(Simulation.Options.of(7, 3, Persona.WARM, dir.resolve("world"))).run()) {
      fixtures = Bakeoff.fixtures(sim, 50);
      var lib = sim.lib;
      assertThat(fixtures).hasSizeBetween(30, 50);
      assertThat(fixtures.stream().map(Bakeoff.Fixture::label).distinct()).contains("political", "ugly", "everyday");
      var file = dir.resolve("fixtures.json");
      Bakeoff.save(fixtures, file);
      assertThat(Bakeoff.load(file)).isEqualTo(fixtures);

      var honest = new RendererTests.StubGateway(FakeRender::render, t -> false);
      var chatty = new RendererTests.StubGateway(text -> {
        var b = FakeRender.render(text);
        return new RenderBatch(b.items().stream().map(i -> new RenderBatch.Item(i.briefId(),
            "It's important to delve into this. " + i.text(), i.gist(), "", List.of())).toList());
      }, t -> false);
      var bakeoff = new Bakeoff(lib);
      var outputs = new ArrayList<Bakeoff.Output>();
      var a = bakeoff.score("honest", honest, fixtures, null, outputs);
      var b = bakeoff.score("chatty", chatty, fixtures, null, outputs);
      assertThat(a.schemaValid()).isEqualTo(1.0);
      assertThat(a.llmIsms()).isZero();
      assertThat(b.llmIsms()).isEqualTo(b.items());
      var out = dir.resolve("run");
      Bakeoff.write(out, List.of(a, b), fixtures, outputs, 7);
      assertThat(Files.readString(out.resolve("report.md"))).contains("| honest |", "| chatty |");
      String side = Files.readString(out.resolve("side-by-side.md"));
      assertThat(side).contains("**A**", "**B**").doesNotContain("honest", "chatty");
      var key = Json.tree(Files.readString(out.resolve("key.json")));
      assertThat(key.size()).isEqualTo(fixtures.size());
    }
  }
}
