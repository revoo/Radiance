package app.radiance.llm.provider;

import static app.radiance.llm.provider.AdapterFixtures.model;
import static app.radiance.llm.provider.AdapterFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import app.radiance.kernel.Json;
import app.radiance.llm.LlmProperties;
import app.radiance.llm.LlmProperties.PromptCaching;
import app.radiance.llm.LlmProperties.StructuredOutput;
import app.radiance.llm.Outcome;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AnthropicLlmTests {

  static final LlmProperties.Provider PROVIDER = new LlmProperties.Provider(
      LlmProperties.Kind.ANTHROPIC, null, "sk-ant", 6, null,
      Map.of("anthropic-workspace-id", "ws_1"), null, null);

  static final LlmProperties.Model HAIKU = model("claude-haiku-5-5", StructuredOutput.SCHEMA,
      PromptCaching.EXPLICIT, Map.of("effort", "low", "thinking", "disabled", "temperature", 0.2));

  final RestClient.Builder builder = RestClient.builder();
  final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

  static final String REPLY = """
      {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5-20261001",
       "content":[{"type":"thinking","thinking":"hmm","signature":"x"},
                  {"type":"text","text":"{\\"answer\\":\\"hi\\","},{"type":"text","text":"\\"n\\":1}"}],
       "stop_reason":"end_turn",
       "usage":{"input_tokens":120,"cache_read_input_tokens":3900,"cache_creation_input_tokens":0,
                "output_tokens":42}}""";

  @Test
  void sendsTheMessagesShapeWithCachingSchemaAndAliasParams() {
    server.expect(requestTo("https://api.anthropic.com/v1/messages"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-ant"))
        .andExpect(header("anthropic-version", "2026-10-21"))
        .andExpect(header("anthropic-workspace-id", "ws_1"))
        .andExpect(jsonPath("$.model").value("claude-haiku-5-5"))
        .andExpect(jsonPath("$.max_tokens").value(300))
        .andExpect(jsonPath("$.system.length()").value(2))
        .andExpect(jsonPath("$.system[0].cache_control").doesNotExist())
        .andExpect(jsonPath("$.system[1].text").value("CAST DIRECTORY"))
        .andExpect(jsonPath("$.system[1].cache_control.type").value("ephemeral"))
        .andExpect(jsonPath("$.messages[0].role").value("user"))
        .andExpect(jsonPath("$.messages[0].content[1].text").value("BRIEFS"))
        .andExpect(jsonPath("$.output_config.format.type").value("json_schema"))
        .andExpect(jsonPath("$.output_config.format.schema.required[0]").value("answer"))
        .andExpect(jsonPath("$.output_config.effort").value("low"))
        .andExpect(jsonPath("$.thinking.type").value("disabled"))
        .andExpect(jsonPath("$.temperature").doesNotExist())
        .andRespond(withSuccess(REPLY, MediaType.APPLICATION_JSON));

    var r = new AnthropicLlm(PROVIDER, builder).complete(request(HAIKU, PROVIDER));
    server.verify();

    assertThat(r.outcome()).isEqualTo(Outcome.OK);
    assertThat(r.text()).isEqualTo("{\"answer\":\"hi\",\"n\":1}");
    assertThat(r.thinkingBlocks()).isEqualTo(1);
    assertThat(r.modelId()).isEqualTo("claude-haiku-5-5-20261001");
    assertThat(r.tokens()).isEqualTo(new ProviderResponse.TokenCounts(120, 3900, 0, 42, 0));
  }

  @Test
  void modelsWithoutSchemaModeGetTheSchemaAtTheEndOfTheCachedPrefix() {
    var plain = model("claude-x", StructuredOutput.NONE, PromptCaching.EXPLICIT, Map.of());
    server.expect(requestTo("https://api.anthropic.com/v1/messages"))
        .andExpect(jsonPath("$.system.length()").value(3))
        .andExpect(jsonPath("$.system[2].text").value(Matchers.containsString("JSON Schema")))
        .andExpect(jsonPath("$.system[2].cache_control.type").value("ephemeral"))
        .andExpect(jsonPath("$.output_config").doesNotExist())
        .andRespond(withSuccess(REPLY, MediaType.APPLICATION_JSON));
    new AnthropicLlm(PROVIDER, builder).complete(request(plain, PROVIDER));
    server.verify();
  }

  @Test
  void normalizesStopReasonsAndErrors() {
    var llm = new AnthropicLlm(PROVIDER, builder);
    server.expect(requestTo("https://api.anthropic.com/v1/messages")).andRespond(withSuccess(
        REPLY.replace("end_turn", "refusal"), MediaType.APPLICATION_JSON));
    server.expect(requestTo("https://api.anthropic.com/v1/messages")).andRespond(withSuccess(
        REPLY.replace("end_turn", "max_tokens"), MediaType.APPLICATION_JSON));
    server.expect(requestTo("https://api.anthropic.com/v1/messages")).andRespond(
        withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "20")
            .body("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}"));
    server.expect(requestTo("https://api.anthropic.com/v1/messages")).andRespond(
        withStatus(org.springframework.http.HttpStatusCode.valueOf(529)).body("{\"error\":{\"type\":\"overloaded_error\"}}"));
    server.expect(requestTo("https://api.anthropic.com/v1/messages")).andRespond(
        withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":{\"type\":\"invalid_request_error\"}}"));

    assertThat(llm.complete(request(HAIKU, PROVIDER)).outcome()).isEqualTo(Outcome.REFUSED);
    assertThat(llm.complete(request(HAIKU, PROVIDER)).outcome()).isEqualTo(Outcome.TRUNCATED);
    var limited = llm.complete(request(HAIKU, PROVIDER));
    assertThat(limited.outcome()).isEqualTo(Outcome.RATE_LIMITED);
    assertThat(limited.retryAfterMs()).isEqualTo(20_000);
    var overloaded = llm.complete(request(HAIKU, PROVIDER));
    assertThat(overloaded.outcome()).isEqualTo(Outcome.ERROR);
    assertThat(overloaded.isClientError()).isFalse();
    var bad = llm.complete(request(HAIKU, PROVIDER));
    assertThat(bad.outcome()).isEqualTo(Outcome.ERROR);
    assertThat(bad.isClientError()).isTrue();
    server.verify();
  }

  @Test
  void imagesTravelAsBase64Blocks() {
    var body = new AnthropicLlm(PROVIDER, builder).body(request(HAIKU, PROVIDER,
        java.util.List.of(app.radiance.llm.Part.text("describe"),
            new app.radiance.llm.Part.Image("image/jpeg", "QUJD")), java.time.Duration.ofSeconds(1)));
    var img = body.at("/messages/0/content/1");
    assertThat(Json.write(img)).isEqualTo(
        "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/jpeg\",\"data\":\"QUJD\"}}");
  }
}
