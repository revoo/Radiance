package app.radiance.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.radiance.RadianceApplication;
import app.radiance.persistence.LeaseHolder;
import app.radiance.persistence.Sql;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

class LlmWiringTests {

  /** Keys are blanked so no test can reach a paid API, whatever is in the environment or .env. */
  static final String[] NO_KEYS = {
    "ANTHROPIC_API_KEY=", "OPENAI_API_KEY=", "GEMINI_API_KEY=", "CF_AI_API_TOKEN=",
    "radiance.llm.providers.ollama.base-url=http://127.0.0.1:9", "radiance.llm.store-prompts=false"
  };

  record Probe(String answer, int n) {}

  @Nested
  @SpringBootTest
  @ActiveProfiles({"offline", "test"})
  class Offline {
    @Autowired LlmGateway gateway;
    @Autowired Sql sql;
    @Autowired LeaseHolder lease;

    @Test
    void runsWithNoKeysAndMetersEveryCallToTheDatabase() throws InterruptedException {
      for (int i = 0; i < 100 && !lease.isHeld(); i++) Thread.sleep(50);
      var r = gateway.call(new LlmRequest<>(Purpose.APPRAISAL, List.of(new Block("bible")),
          List.of(Part.text("R's post")), Probe.class, 50, Duration.ofSeconds(5), Priority.LIVE));
      assertThat(r.ok()).isTrue();
      assertThat(gateway.budgetFor(Purpose.RENDER_THREAD).maxPromptTokens()).isEqualTo(24_000);

      List<String> rows = List.of();
      for (int i = 0; i < 100 && rows.isEmpty(); i++) {
        Thread.sleep(20);
        rows = sql.query("SELECT provider FROM llm_call WHERE id = ?", List.of(r.callId()),
            row -> row.str("provider"));
      }
      assertThat(rows).containsExactly(RouteTable.FAKE_PROVIDER);
    }
  }

  @Nested
  @SpringBootTest(properties = {
    "radiance.llm.mode=live",
    "ANTHROPIC_API_KEY=", "OPENAI_API_KEY=", "GEMINI_API_KEY=", "CF_AI_API_TOKEN=",
    "radiance.llm.store-prompts=false"
  })
  @ActiveProfiles({"offline", "test"})
  class LiveModeWithoutKeys {
    @Autowired LlmProperties props;
    @Autowired RouteTable routes;

    @Test
    void bindsTheSpecConfigAndFallsBackToFakeForKeylessRoutes() {
      var openai = props.providers().get("openai");
      assertThat(openai.kind()).isEqualTo(LlmProperties.Kind.OPENAI_COMPATIBLE);
      assertThat(openai.maxTokensField()).isEqualTo("max_completion_tokens");
      assertThat(props.providers().get("ollama").hasKey()).as("a local runner needs no key").isTrue();
      var haiku = props.models().get("haiku");
      assertThat(haiku.capabilities().structuredOutput()).isEqualTo(LlmProperties.StructuredOutput.SCHEMA);
      assertThat(haiku.capabilities().promptCaching()).isEqualTo(LlmProperties.PromptCaching.EXPLICIT);
      assertThat(haiku.params()).containsEntry("effort", "low").containsEntry("thinking", "disabled");
      // decision 18: Haiku on every route in V1; sonnet stays defined for the V2 bake-off
      assertThat(props.routes().values()).allSatisfy(r -> assertThat(r.model()).isEqualTo("haiku"));
      assertThat(props.models()).containsKey("sonnet");
      for (var p : Purpose.values()) assertThat(routes.targets(p)).containsExactly(RouteTable.FAKE);
    }
  }

  @Test
  void aRouteWhoseModelLacksANeededCapabilityFailsStartup() {
    // Command-line arguments outrank application.yml; builder.properties() would not.
    var args = new java.util.ArrayList<String>();
    for (String kv : NO_KEYS) args.add("--" + kv);
    args.addAll(List.of(
        "--server.port=0",
        "--radiance.llm.models.blind.provider=anthropic",
        "--radiance.llm.models.blind.id=claude-text-only",
        "--radiance.llm.routes.vision-appraisal.model=blind"));
    var app = new SpringApplicationBuilder(RadianceApplication.class).profiles("offline", "test");
    assertThatThrownBy(() -> app.run(args.toArray(String[]::new)).close())
        .hasStackTraceContaining("route 'vision-appraisal': model 'blind' lacks vision");
  }
}
