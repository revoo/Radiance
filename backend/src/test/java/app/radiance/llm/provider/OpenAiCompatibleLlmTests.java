package app.radiance.llm.provider;

import static app.radiance.llm.provider.AdapterFixtures.model;
import static app.radiance.llm.provider.AdapterFixtures.provider;
import static app.radiance.llm.provider.AdapterFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import app.radiance.llm.LlmProperties.PromptCaching;
import app.radiance.llm.LlmProperties.StructuredOutput;
import app.radiance.llm.Outcome;
import app.radiance.llm.Part;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleLlmTests {

  static final String URL = "https://api.example.com/v1/chat/completions";

  final RestClient.Builder builder = RestClient.builder();
  final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

  static String reply(String content, String finish) {
    return """
        {"id":"c1","model":"gpt-x-2026","choices":[{"index":0,"finish_reason":"%s",
          "message":{"role":"assistant","content":%s,"refusal":null}}],
         "usage":{"prompt_tokens":5000,"completion_tokens":300,
          "prompt_tokens_details":{"cached_tokens":4096},
          "completion_tokens_details":{"reasoning_tokens":120}}}"""
        .formatted(finish, content);
  }

  @Test
  void schemaModelsGetStrictJsonSchemaAndAliasParams() {
    var m = model("gpt-x", StructuredOutput.SCHEMA, PromptCaching.AUTOMATIC,
        Map.of("reasoning_effort", "none"));
    var p = provider("https://api.example.com/v1", "max_completion_tokens");
    server.expect(requestTo(URL))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test"))
        .andExpect(header("x-extra", "1"))
        .andExpect(jsonPath("$.max_completion_tokens").value(300))
        .andExpect(jsonPath("$.max_tokens").doesNotExist())
        .andExpect(jsonPath("$.messages[0].role").value("system"))
        .andExpect(jsonPath("$.messages[0].content").value("WORLD BIBLE\n\nCAST DIRECTORY"))
        .andExpect(jsonPath("$.messages[1].content").value("TODAY\n\nBRIEFS"))
        .andExpect(jsonPath("$.response_format.type").value("json_schema"))
        .andExpect(jsonPath("$.response_format.json_schema.name").value("Probe"))
        .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
        .andExpect(jsonPath("$.response_format.json_schema.schema.additionalProperties").value(false))
        .andExpect(jsonPath("$.reasoning_effort").value("none"))
        .andRespond(withSuccess(reply("\"{\\\"answer\\\":\\\"hi\\\",\\\"n\\\":1}\"", "stop"),
            MediaType.APPLICATION_JSON));

    var r = new OpenAiCompatibleLlm(p, builder).complete(request(m, p));
    server.verify();
    assertThat(r.outcome()).isEqualTo(Outcome.OK);
    assertThat(r.text()).isEqualTo("{\"answer\":\"hi\",\"n\":1}");
    assertThat(r.modelId()).isEqualTo("gpt-x-2026");
    assertThat(r.tokens()).as("cached out of input; reasoning stays in output")
        .isEqualTo(new ProviderResponse.TokenCounts(904, 4096, 0, 300, 120));
  }

  @Test
  void jsonModeModelsGetJsonObjectAndTheSchemaInTheSystemMessage() {
    var m = model("llama", StructuredOutput.JSON, PromptCaching.NONE, Map.of("strict", false));
    var p = provider("https://api.example.com/v1/", null);
    server.expect(requestTo(URL))
        .andExpect(jsonPath("$.max_tokens").value(300))
        .andExpect(jsonPath("$.response_format.type").value("json_object"))
        .andExpect(jsonPath("$.messages[0].content").value(Matchers.containsString("JSON Schema")))
        .andExpect(jsonPath("$.strict").doesNotExist())
        .andRespond(withSuccess(reply("\"{}\"", "stop"), MediaType.APPLICATION_JSON));
    new OpenAiCompatibleLlm(p, builder).complete(request(m, p));
    server.verify();
  }

  @Test
  void normalizesRefusalsTruncationFiltersAndErrors() {
    var m = model("gpt-x", StructuredOutput.SCHEMA, PromptCaching.AUTOMATIC, Map.of());
    var p = provider("https://api.example.com/v1", null);
    var llm = new OpenAiCompatibleLlm(p, builder);
    server.expect(requestTo(URL)).andRespond(withSuccess(reply("null", "stop")
        .replace("\"refusal\":null", "\"refusal\":\"I can't help with that\""), MediaType.APPLICATION_JSON));
    server.expect(requestTo(URL)).andRespond(withSuccess(reply("\"{\\\"a\"", "length"),
        MediaType.APPLICATION_JSON));
    server.expect(requestTo(URL)).andRespond(withSuccess(reply("\"\"", "content_filter"),
        MediaType.APPLICATION_JSON));
    server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(
        "{\"error\":{\"code\":429,\"details\":[{\"retryDelay\":\"37s\"}]}}"));
    server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body(
        "{\"errors\":[{\"message\":\"JSON Mode couldn't be met\"}],\"success\":false}"));
    server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

    assertThat(llm.complete(request(m, p)).outcome()).isEqualTo(Outcome.REFUSED);
    assertThat(llm.complete(request(m, p)).outcome()).isEqualTo(Outcome.TRUNCATED);
    assertThat(llm.complete(request(m, p)).outcome()).isEqualTo(Outcome.REFUSED);
    var limited = llm.complete(request(m, p));
    assertThat(limited.outcome()).isEqualTo(Outcome.RATE_LIMITED);
    assertThat(limited.retryAfterMs()).isEqualTo(37_000);
    assertThat(llm.complete(request(m, p)).outcome()).isEqualTo(Outcome.INVALID_OUTPUT);
    var bad = llm.complete(request(m, p));
    assertThat(bad.outcome()).isEqualTo(Outcome.ERROR);
    assertThat(bad.isClientError()).isFalse();
    server.verify();
  }

  @Test
  void readsTheWorkersAiEnvelope() {
    var r = OpenAiCompatibleLlm.parse(app.radiance.kernel.Json.tree(
        "{\"result\":{\"response\":{\"answer\":\"hi\",\"n\":1},\"usage\":{\"prompt_tokens\":10,"
            + "\"completion_tokens\":5}},\"success\":true}"), "@cf/meta/llama");
    assertThat(r.outcome()).isEqualTo(Outcome.OK);
    assertThat(r.text()).isEqualTo("{\"answer\":\"hi\",\"n\":1}");
    assertThat(r.tokens().input()).isEqualTo(10);
  }

  @Test
  void imagesBecomeDataUrls() {
    var m = model("gpt-x", StructuredOutput.SCHEMA, PromptCaching.AUTOMATIC, Map.of());
    var p = provider("https://api.example.com/v1", null);
    var body = new OpenAiCompatibleLlm(p, builder).body(request(m, p,
        List.of(Part.text("what is this"), new Part.Image("image/png", "QUJD")), Duration.ofSeconds(1)));
    assertThat(body.at("/messages/1/content/1/image_url/url").asString())
        .isEqualTo("data:image/png;base64,QUJD");
  }

  @Test
  void aSlowProviderTimesOut() {
    var m = model("gpt-x", StructuredOutput.SCHEMA, PromptCaching.AUTOMATIC, Map.of());
    var p = provider("https://api.example.com/v1", null);
    server.expect(requestTo(URL)).andRespond(req -> {
      try {
        Thread.sleep(2_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return withSuccess(reply("\"{}\"", "stop"), MediaType.APPLICATION_JSON).createResponse(req);
    });
    var r = new OpenAiCompatibleLlm(p, builder).complete(request(m, p,
        List.of(Part.text("x")), Duration.ofMillis(200)));
    assertThat(r.outcome()).isEqualTo(Outcome.TIMEOUT);
  }
}
