package app.radiance.narrative;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.kernel.Json;
import app.radiance.persistence.UnitOfWork;
import app.radiance.sim.Persona;
import app.radiance.sim.Simulation;
import app.radiance.sim.model.Tag;
import app.radiance.story.ConstraintRepository;
import app.radiance.story.ConstraintRow;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M5's acceptance fixture (SPEC §12): 7 scripted days of a constraint-heavy world — Jessica and Nick's
 * secret romance, Jesse under {@code budget_tight} while friends plan a Bahamas trip, Kendra with
 * {@code injured:knee} before a group hike — rendered by an adversarial model that writes the violation
 * every first time. Zero violations may reach aired prose, and no blacklisted theme ever does.
 */
class ConstraintFixtureTests {

  static final String BAHAMAS = "booking the Bahamas trip tonight, first class baby";
  static final String HIKE = "hike Saturday, 12 miles, who's in";
  static final String SECRET = "lol Jessica and Nick are totally dating";
  static final String THEME = "she's pregnant again";
  static final List<String> MARKERS = List.of(BAHAMAS, HIKE, SECRET, THEME);

  @TempDir Path dir;

  /** Writes each constraint's violation on the first attempt, and clean text when asked to fix it. */
  static final class Adversary {
    final AtomicInteger injected = new AtomicInteger();
    final Map<String, AtomicInteger> byKind = new java.util.concurrent.ConcurrentHashMap<>();

    String add(String text, String marker) {
      byKind.computeIfAbsent(marker, k -> new AtomicInteger()).incrementAndGet();
      return text + " — " + marker;
    }

    RenderBatch render(String content) {
      var honest = FakeRender.render(content);
      if (content.contains("<fix>")) return honest;
      var constraints = constraintsByHandle(content);
      int from = content.indexOf("<briefs>"), to = content.indexOf("</briefs>");
      var briefs = Json.tree(content.substring(from + 8, to).strip());
      var byId = new HashMap<String, tools.jackson.databind.JsonNode>();
      briefs.forEach(b -> byId.put(b.path("briefId").asString(), b));
      var items = new ArrayList<RenderBatch.Item>();
      for (var i : honest.items()) {
        var b = byId.get(i.briefId());
        String author = b == null ? "" : b.path("author").asString();
        String c = constraints.getOrDefault(author, "");
        String text = i.text();
        if (c.contains("is broke right now")) text = add(text, BAHAMAS);
        if (c.contains("has an injured")) text = add(text, HIKE);
        if (b != null && b.path("constraints").toString().contains("doesn't know about Jessica")) text = add(text, SECRET);
        if (Math.floorMod(i.briefId().hashCode(), 7) == 0) text = add(text, THEME);
        if (!text.equals(i.text())) injected.incrementAndGet();
        items.add(new RenderBatch.Item(i.briefId(), text, i.gist(), i.recapLine(), i.introducedFacts()));
      }
      return new RenderBatch(items);
    }

    static Map<String, String> constraintsByHandle(String content) {
      var out = new HashMap<String, String>();
      var m = Pattern.compile("## (@[a-z.]+) [^\\n]*\\n((?:(?!## ).*\\n)*)").matcher(content);
      while (m.find()) {
        var c = Pattern.compile("Constraints: (.*)").matcher(m.group(2));
        if (c.find()) out.put(m.group(1), c.group(1));
      }
      return out;
    }
  }

  @Test
  void sevenDaysOfConstraintsAndSecretsAndNothingSlipsThrough() {
    var adversary = new Adversary();
    var gateway = new RendererTests.StubGateway(adversary::render,
        text -> MARKERS.stream().anyMatch(text::contains));
    var run = new RenderedRun(gateway);
    var once = new java.util.concurrent.atomic.AtomicBoolean();
    var options = Simulation.Options.of(7, 7, Persona.WARM, dir).rendering(sim -> {
      if (once.compareAndSet(false, true)) constrain(sim);
      run.renderNight(sim);
    });
    try (var sim = new Simulation(options).run()) {
      var bodies = sim.sql.query("SELECT body FROM post WHERE body IS NOT NULL AND origin <> 'USER'"
          + " UNION ALL SELECT body FROM comment WHERE body IS NOT NULL AND origin <> 'USER'"
          + " UNION ALL SELECT body FROM dm_message WHERE body IS NOT NULL", List.of(), r -> r.str("body"));
      assertThat(bodies).hasSizeGreaterThan(200);
      assertThat(adversary.injected.get()).as("the adversary got its chance").isGreaterThan(20);
      assertThat(adversary.byKind).as("every kind of violation was tried: " + adversary.byKind)
          .containsKeys(BAHAMAS, HIKE, THEME);
      assertThat(bodies).as("unhandled violations in rendered prose").noneMatch(b -> MARKERS.stream().anyMatch(b::contains));
      var linter = sim.lib.themeLinter();
      assertThat(bodies).as("blacklisted themes").allMatch(b -> linter.hits(b).isEmpty());
      int rerendered = run.results.stream().mapToInt(Renderer.Result::rerendered).sum();
      assertThat(rerendered).isPositive();
      assertThat(run.nights).allSatisfy(n -> assertThat(n.jobs().failed()).isZero());
    }
  }

  /** Jesse is broke for a month and Kendra hurt her knee, from the first night on. */
  static void constrain(Simulation sim) {
    var w = sim.engine.world();
    var h = w.byHandle();
    long now = w.time;
    var rows = new ArrayList<ConstraintRow>();
    for (var e : List.of(Map.entry("jesse.hensley", "budget_tight"), Map.entry("kendra.gaines", "injured:knee"))) {
      var p = h.get(e.getKey());
      var tag = new Tag(e.getValue(), now, now + 30 * 86_400_000L, null);
      w.tags.computeIfAbsent(p.id, k -> new java.util.TreeMap<>()).put(tag.tag(), tag);
      rows.add(new ConstraintRow(p.id, tag.tag(), tag.since(), tag.until(), null));
    }
    sim.sqlLane().run(() -> sim.sql.commit(new ConstraintRepository(sim.sql).save(new UnitOfWork(), rows)));
  }
}
