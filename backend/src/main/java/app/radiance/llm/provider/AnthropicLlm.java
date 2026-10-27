package app.radiance.llm.provider;

import app.radiance.kernel.Json;
import app.radiance.llm.Block;
import app.radiance.llm.LlmProperties;
import app.radiance.llm.Outcome;
import app.radiance.llm.Part;
import java.net.URI;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Anthropic Messages API, called directly (SPEC §5.10, Appendix C; API version 2026-10-21).
 *
 * <ul>
 *   <li>The prefix goes in {@code system}; with EXPLICIT caching its last block carries the
 *       {@code cache_control} breakpoint.
 *   <li>SCHEMA models get {@code output_config.format}; others get the schema at the end of the prefix.
 *   <li>Alias params: {@code effort} → {@code output_config.effort}; {@code thinking} →
 *       {@code thinking.type}; anything else is copied into the body as is. Sampling parameters are
 *       never sent, whatever the alias says: this API version rejects them.
 *   <li>Responses are read by block type; thinking blocks are counted and skipped.
 * </ul>
 */
public final class AnthropicLlm implements LlmProvider {

  public static final String API_VERSION = "2026-10-21";
  static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
  private static final java.util.Set<String> NEVER_SEND = java.util.Set.of("temperature", "top_p", "top_k");

  private final RestClient client;
  private final LlmProperties.Provider provider;
  private final URI endpoint;

  public AnthropicLlm(LlmProperties.Provider provider, RestClient.Builder builder) {
    this.provider = provider;
    String base = provider.baseUrl() == null ? DEFAULT_BASE_URL : provider.baseUrl();
    this.client = builder.clone().build();
    this.endpoint = URI.create(Http.join(base, "/v1/messages"));
  }

  @Override
  public ProviderResponse complete(ProviderRequest req) {
    var body = body(req);
    String modelId = req.model().id();
    return Http.withTimeout(req.timeout(), modelId, () -> {
      var raw = client.post()
          .uri(endpoint)
          .contentType(MediaType.APPLICATION_JSON)
          .headers(h -> {
            if (provider.apiKey() != null) h.setBearerAuth(provider.apiKey());
            h.set("anthropic-version", API_VERSION);
            provider.headers().forEach(h::set);
          })
          .body(Json.write(body))
          .exchange(Http.raw());
      if (raw.status() / 100 != 2) return Http.failure(raw, modelId, b -> null);
      return parse(Json.tree(raw.body()), modelId);
    });
  }

  ObjectNode body(ProviderRequest req) {
    var caps = req.model().capabilities();
    var body = Json.MAPPER.createObjectNode();
    body.put("model", req.model().id());
    body.put("max_tokens", req.maxOutputTokens());

    var prefix = new ArrayList<>(req.prefix());
    if (caps.structuredOutput() != LlmProperties.StructuredOutput.SCHEMA) {
      prefix.add(SchemaPrompt.block(req.schema()));
    }
    var system = body.putArray("system");
    for (int i = 0; i < prefix.size(); i++) {
      Block b = prefix.get(i);
      var block = system.addObject().put("type", "text").put("text", b.text());
      if (i == prefix.size() - 1 && caps.promptCaching() == LlmProperties.PromptCaching.EXPLICIT) {
        block.putObject("cache_control").put("type", "ephemeral");
      }
    }

    var content = body.putArray("messages").addObject().put("role", "user").putArray("content");
    for (Part p : req.content()) {
      switch (p) {
        case Part.Text t -> content.addObject().put("type", "text").put("text", t.text());
        case Part.Image img -> content.addObject().put("type", "image").putObject("source")
            .put("type", "base64").put("media_type", img.mime()).put("data", img.base64());
      }
    }

    ObjectNode outputConfig = null;
    if (caps.structuredOutput() == LlmProperties.StructuredOutput.SCHEMA) {
      outputConfig = body.putObject("output_config");
      outputConfig.putObject("format").put("type", "json_schema").set("schema", req.schema());
    }
    for (Map.Entry<String, Object> param : req.model().params().entrySet()) {
      String key = param.getKey();
      if (NEVER_SEND.contains(key)) continue;
      switch (key) {
        case "effort" -> {
          if (outputConfig == null) outputConfig = body.putObject("output_config");
          outputConfig.put("effort", String.valueOf(param.getValue()));
        }
        case "thinking" -> body.putObject("thinking").put("type", String.valueOf(param.getValue()));
        default -> body.set(key, Json.MAPPER.valueToTree(param.getValue()));
      }
    }
    return body;
  }

  static ProviderResponse parse(JsonNode root, String fallbackModel) {
    var text = new StringBuilder();
    int thinking = 0;
    for (JsonNode block : root.path("content")) {
      switch (block.path("type").asString()) {
        case "text" -> text.append(block.path("text").asString());
        case "thinking", "redacted_thinking" -> thinking++;
        default -> { }
      }
    }
    var usage = root.path("usage");
    var tokens = new ProviderResponse.TokenCounts(
        usage.path("input_tokens").asInt(0),
        usage.path("cache_read_input_tokens").asInt(0),
        usage.path("cache_creation_input_tokens").asInt(0),
        usage.path("output_tokens").asInt(0),
        0);
    Outcome outcome = switch (root.path("stop_reason").asString()) {
      case "refusal" -> Outcome.REFUSED;
      case "max_tokens" -> Outcome.TRUNCATED;
      default -> Outcome.OK;
    };
    String model = root.path("model").asString(fallbackModel);
    return new ProviderResponse(outcome, text.toString(), tokens, model, thinking, null,
        outcome == Outcome.OK ? null : "stop_reason " + root.path("stop_reason").asString());
  }
}
