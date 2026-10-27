package app.radiance.llm.provider;

import app.radiance.kernel.Json;
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
 * The Chat Completions format that OpenAI and many others accept: Gemini's and Workers AI's
 * OpenAI-compatible endpoints, Ollama and vLLM (SPEC §5.10).
 *
 * <ul>
 *   <li>The prefix becomes one system message; for JSON and NONE models the schema is appended to it.
 *   <li>SCHEMA → {@code response_format: json_schema} (strict unless the alias sets
 *       {@code strict: false}); JSON → {@code json_object}; NONE → nothing.
 *   <li>Alias params other than {@code strict} are copied into the body as is
 *       ({@code reasoning_effort}, sampling where a provider takes it, extra fields).
 *   <li>Usage is normalized: cached tokens come out of {@code input}; reasoning stays in {@code output}.
 * </ul>
 */
public final class OpenAiCompatibleLlm implements LlmProvider {

  private final RestClient client;
  private final LlmProperties.Provider provider;
  private final URI endpoint;

  public OpenAiCompatibleLlm(LlmProperties.Provider provider, RestClient.Builder builder) {
    this.provider = provider;
    this.client = builder.clone().build();
    this.endpoint = URI.create(Http.join(provider.baseUrl(), "/chat/completions"));
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
            if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
              h.setBearerAuth(provider.apiKey());
            }
            provider.headers().forEach(h::set);
          })
          .body(Json.write(body))
          .exchange(Http.raw());
      if (raw.status() / 100 != 2) {
        return Http.failure(raw, modelId, OpenAiCompatibleLlm::schemaNotMet);
      }
      return parse(Json.tree(raw.body()), modelId);
    });
  }

  /** Workers AI reports an unmet JSON schema as an error. */
  private static Outcome schemaNotMet(String body) {
    return body != null && body.contains("JSON Mode couldn't be met") ? Outcome.INVALID_OUTPUT : null;
  }

  ObjectNode body(ProviderRequest req) {
    var mode = req.model().capabilities().structuredOutput();
    var body = Json.MAPPER.createObjectNode();
    body.put("model", req.model().id());
    String maxField = provider.maxTokensField() == null ? "max_tokens" : provider.maxTokensField();
    body.put(maxField, req.maxOutputTokens());

    var system = new ArrayList<String>();
    req.prefix().forEach(b -> system.add(b.text()));
    if (mode != LlmProperties.StructuredOutput.SCHEMA) system.add(SchemaPrompt.block(req.schema()).text());
    var messages = body.putArray("messages");
    messages.addObject().put("role", "system").put("content", String.join("\n\n", system));

    var user = messages.addObject().put("role", "user");
    boolean hasImages = req.content().stream().anyMatch(p -> p instanceof Part.Image);
    if (!hasImages) {
      var text = new StringBuilder();
      for (Part p : req.content()) {
        if (!text.isEmpty()) text.append("\n\n");
        text.append(((Part.Text) p).text());
      }
      user.put("content", text.toString());
    } else {
      var parts = user.putArray("content");
      for (Part p : req.content()) {
        switch (p) {
          case Part.Text t -> parts.addObject().put("type", "text").put("text", t.text());
          case Part.Image img -> parts.addObject().put("type", "image_url").putObject("image_url")
              .put("url", "data:" + img.mime() + ";base64," + img.base64());
        }
      }
    }

    boolean strict = !Boolean.FALSE.equals(req.model().params().get("strict"))
        && !"false".equals(String.valueOf(req.model().params().get("strict")));
    switch (mode) {
      case SCHEMA -> {
        var js = body.putObject("response_format").put("type", "json_schema").putObject("json_schema");
        js.put("name", req.schemaName()).put("strict", strict).set("schema", req.schema());
      }
      case JSON -> body.putObject("response_format").put("type", "json_object");
      case NONE -> { }
    }
    for (Map.Entry<String, Object> param : req.model().params().entrySet()) {
      if (!param.getKey().equals("strict")) {
        body.set(param.getKey(), Json.MAPPER.valueToTree(param.getValue()));
      }
    }
    return body;
  }

  static ProviderResponse parse(JsonNode root, String fallbackModel) {
    // Workers AI's own envelope: {"result": {...}, "success": true}
    if (root.has("result") && root.get("result").isObject()) root = root.get("result");
    String model = root.path("model").asString(fallbackModel);
    var usage = root.path("usage");
    int prompt = usage.path("prompt_tokens").asInt(0);
    int cached = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
    var tokens = new ProviderResponse.TokenCounts(
        Math.max(0, prompt - cached), cached, 0,
        usage.path("completion_tokens").asInt(0),
        usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0));

    var choice = root.path("choices").path(0);
    if (choice.isMissingNode()) {
      var response = root.get("response"); // the native Workers AI shape
      if (response == null) return ProviderResponse.failure(Outcome.ERROR, model, "no choices in response");
      String text = response.isString() ? response.asString() : response.toString();
      return new ProviderResponse(Outcome.OK, text, tokens, model, 0, null, null);
    }
    var message = choice.path("message");
    if (message.hasNonNull("refusal") && !message.get("refusal").asString().isBlank()) {
      return new ProviderResponse(Outcome.REFUSED, null, tokens, model, 0, null,
          message.get("refusal").asString());
    }
    String text = content(message.path("content"));
    Outcome outcome = switch (choice.path("finish_reason").asString()) {
      case "length" -> Outcome.TRUNCATED;
      case "content_filter" -> Outcome.REFUSED;
      default -> Outcome.OK;
    };
    return new ProviderResponse(outcome, text, tokens, model, 0, null,
        outcome == Outcome.OK ? null : "finish_reason " + choice.path("finish_reason").asString());
  }

  private static String content(JsonNode content) {
    if (content.isString()) return content.asString();
    if (content.isArray()) {
      var sb = new StringBuilder();
      for (JsonNode part : content) {
        if ("text".equals(part.path("type").asString())) sb.append(part.path("text").asString());
      }
      return sb.toString();
    }
    return null;
  }
}
