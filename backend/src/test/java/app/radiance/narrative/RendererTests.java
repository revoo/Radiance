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
    // R's own posts have their words already, but the answers to them still need writing (live play does those as they come)
    return sim.sql.query("SELECT id FROM post WHERE air_status <> 'DROPPED' AND (body IS NULL OR id IN (SELECT post_id FROM comment WHERE body IS NULL))"
        + " ORDER BY created_at",
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

  /** Regression: Amra answered Deepa as "Noted. Also Amra, Lucy is not a team captain", and Lisa wrote "no wait, I'm Lisa". */
  @Test
  void aModelLosingTrackOfWhoIsTalkingIsCaught() {
    assertThat(ProseChecks.slipped("Lisa Marchetti", "my mother's recipe... no wait, I'm Lisa and I went home")).isTrue();
    assertThat(ProseChecks.slipped("Amra Hodzic", "Noted. Also Amra, Lucy is not a team captain.")).isTrue();
    assertThat(ProseChecks.slipped("Amra Hodzic", "Amra, you of all people")).isTrue();
    assertThat(ProseChecks.slipped("Amra Hodzic", "Deepa, Lucy is fine. Amra out.")).isFalse();
    assertThat(ProseChecks.slipped("Lukas Petrauskas", "Booked a flight to Ireland... I mean Lithuania.")).isTrue();
    assertThat(ProseChecks.slipped("Jessica Vogel", "Home in Des Plaines... wait no, Mount Prospect")).isTrue();
  }

  /** Regression: every text from Jessica ended "sorry not sorry", and she gave R her address three times. */
  @Test
  void someoneSayingItAgainIsCaught() {
    String packet = """
        <people>sorry not sorry about the pasta wait, in a sample post</people>
        <conversation>
        @russ.rose: where do you live? sorry not sorry about the pasta wait
        @jessica.vogel: West Loop, the Fulton side. Buzz me when you get here.
        @jessica.vogel: Mabel says hi. Sorry not sorry.
        </conversation>
        <briefs>[]</briefs>
        """;
    var jessica = "@jessica.vogel";
    var phrases = java.util.List.of("sorry not sorry");
    assertThat(ProseChecks.repeats("lol. Buzz me when you get here, ok", packet, jessica, phrases)).isEqualTo("buzz me when you get");
    assertThat(ProseChecks.repeats("toast is going, SORRY not sorry!", packet, jessica, phrases)).isEqualTo("sorry not sorry");
    assertThat(ProseChecks.repeats("my day was long. Nick is annoying me.", packet, jessica, phrases)).isNull();
    // R's words and her sample posts aren't her saying it again
    assertThat(ProseChecks.repeats("where do you live? tell me", packet, jessica, java.util.List.of())).isNull();
    assertThat(ProseChecks.repeats("about the pasta wait last week", packet, jessica, java.util.List.of())).isNull();
    // and a thread's comments count, indented as they are
    assertThat(ProseChecks.repeats("I am so proud of you all", "<thread>\n  @a.b: I am so proud of you\n</thread>", "@a.b", java.util.List.of())).isEqualTo("i am so proud of");
  }

  /** Regression: Rick sent Nick's whole comment back, down to Nick's aside about a Jewel coupon. */
  @Test
  void copyingSomeoneElsesLineIsCaught_ButQuotingAFewWordsIsnt() {
    String packet = """
        <thread>
        @gary.moran (status post): nobody read the bill
          @nick.grabowski: read chapter 1 before you tell Tim to sit down, the calendar is public
        </thread>
        <briefs>[]</briefs>
        """;
    assertThat(ProseChecks.repeats("Gary, read chapter 1 before you tell Tim to sit down.", packet, "@ricardo.ocampo", java.util.List.of()))
        .isEqualTo("read chapter 1 before you tell tim");
    assertThat(ProseChecks.repeats("sit down? you sit down, Nick", packet, "@ricardo.ocampo", java.util.List.of())).isNull();
    assertThat(ProseChecks.repeats("read chapter 1? nobody has", packet, "@ricardo.ocampo", java.util.List.of())).isNull();
  }

  @Test
  void aTextSentWordForWordAgainIsCaught_ButAShortOneIsJustTalking() {
    String packet = "<conversation>\n@r.ocampo: ok bro, six o'clock, got it\n@r.ocampo: ok bro\n</conversation>\n<briefs>[]</briefs>";
    assertThat(ProseChecks.sentAlready("Ok bro, six o'clock, got it!", packet, "@r.ocampo")).isTrue();
    assertThat(ProseChecks.sentAlready("ok bro", packet, "@r.ocampo")).isFalse();
    assertThat(ProseChecks.sentAlready("ok bro, seven o'clock, got it", packet, "@r.ocampo")).isFalse();
  }

  /** Regression: "I'm at 3-something Rockwell... no wait, you're asking where I live." */
  @Test
  void catchingYourselfMidTextIsASlip() {
    assertThat(ProseChecks.slipped("Cody Garrett", "a charge nurse thing at megan's place tonight, no wait that's not mine")).isTrue();
    assertThat(ProseChecks.slipped("Jessica Vogel", "I'm at 3-something Rockwell... no wait, you're asking where I live.")).isTrue();
  }

  /** Regression: "Sox lost AGAIN last night", on the first of November. */
  @Test
  void nobodyWatchesBaseballOutOfSeason() {
    var november = java.time.LocalDate.of(2026, 11, 1);
    assertThat(ProseChecks.baseballOutOfSeason("Sox lost AGAIN last night", november)).isTrue();
    assertThat(ProseChecks.baseballOutOfSeason("That bullpen, the Cubs game was a mess", november)).isTrue();
    assertThat(ProseChecks.baseballOutOfSeason("Sox are done till April, so it's just the Bears", november)).isFalse();
    assertThat(ProseChecks.baseballOutOfSeason("Sox lost AGAIN last night", java.time.LocalDate.of(2026, 7, 1))).isFalse();
  }

  /** Regression: the model names people "Lukas Petrauskas", we only knew @lukas.petrauskas, and nearly every invented detail was lost: Andre's nephew became his son. */
  @Test
  void aFactNamingSomeoneByTheirNameIsStillKept() {
    try (var sim = new Simulation(Simulation.Options.of(7, 1, Persona.WARM, dir)).run()) {
      var person = sim.engine.world().residents().getFirst();
      Function<String, RenderBatch> facts = text -> new RenderBatch(FakeRender.render(text).items().stream().map(i -> new RenderBatch.Item(i.briefId(), i.text(), i.gist(), i.recapLine(),
          List.of(new RenderBatch.Fact(person.name, "has a nephew, Marcus, 7", false)))).toList());
      var renderer = new Renderer(sim.sql, sim.sqlLane(), sim.lib, new StubGateway(facts, t -> false), sim.engine.world(), "Radiance", true, Priority.SCRIPT);
      String future = sim.sql.queryOne("SELECT id FROM post WHERE air_status = 'SCRIPTED' AND body IS NULL ORDER BY created_at LIMIT 1", List.of(), r -> r.str("id")).orElseThrow();
      renderer.renderThread(future);
      assertThat(sim.sql.queryOne("SELECT COUNT(*) AS n FROM fact WHERE subject_id = ? AND statement = 'has a nephew, Marcus, 7'", List.of(person.id), r -> r.lng("n")).orElseThrow()).isPositive();
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
