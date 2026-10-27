package app.radiance.llm.provider;

import app.radiance.kernel.Json;
import app.radiance.kernel.RandomSource;
import app.radiance.llm.Block;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.Part;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deterministic, free, offline (SPEC §5.10): the whole app runs on it with zero keys. By default it
 * fills the output schema with values derived from a hash of the request, so the same request always
 * gets the same answer. Purposes that need believable output register a template for their output
 * type (the render templates arrive in M5).
 */
public final class FakeLlm implements LlmProvider {

  public static final String ALIAS = "fake";

  private final Map<Class<?>, Function<ProviderRequest, Object>> templates = new ConcurrentHashMap<>();
  private final FakeResponses registered;

  public FakeLlm() {
    this(new FakeResponses());
  }

  /** Uses templates domain modules registered for their output types. */
  public FakeLlm(FakeResponses registered) {
    this.registered = registered;
  }

  /** Answers requests for {@code type} with {@code template} instead of schema-filled values. */
  public <T> FakeLlm template(Class<T> type, Function<ProviderRequest, T> template) {
    templates.put(type, template::apply);
    return this;
  }

  @Override
  public ProviderResponse complete(ProviderRequest request) {
    var template = templates.get(request.outputType());
    var domain = registered.forType(request.outputType());
    String text;
    if (template != null) {
      text = Json.write(template.apply(request));
    } else if (domain.isPresent()) {
      var call = new FakeResponses.FakeCall(request.purpose(),
          request.prefix().stream().map(Block::text).toList(),
          request.content().stream().map(p -> p instanceof Part.Text t ? t.text() : "[image]").toList());
      text = Json.write(domain.get().apply(call));
    } else {
      text = fill(request.schema(), "", RandomSource.seeded(hash(request))).toString();
    }
    return ProviderResponse.ok(text, ProviderResponse.TokenCounts.ZERO, ALIAS);
  }

  private static JsonNode fill(JsonNode schema, String field, RandomSource rng) {
    return switch (schema.path("type").asString()) {
      case "object" -> {
        ObjectNode out = Json.MAPPER.createObjectNode();
        for (var entry : schema.path("properties").properties()) {
          out.set(entry.getKey(), fill(entry.getValue(), entry.getKey(), rng));
        }
        yield out;
      }
      case "array" -> {
        var arr = Json.MAPPER.createArrayNode();
        arr.add(fill(schema.get("items"), field, rng));
        yield arr;
      }
      case "string" -> {
        if (schema.has("enum")) {
          var values = schema.get("enum");
          yield values.get(rng.nextInt(values.size()));
        }
        yield Json.MAPPER.getNodeFactory().stringNode("fake " + field + " " + rng.nextInt(1000));
      }
      case "integer" -> Json.MAPPER.getNodeFactory().numberNode(rng.nextInt(10));
      case "number" -> Json.MAPPER.getNodeFactory().numberNode(Math.round(rng.nextDouble() * 100) / 100.0);
      case "boolean" -> Json.MAPPER.getNodeFactory().booleanNode(rng.chance(0.5));
      default -> Json.MAPPER.getNodeFactory().nullNode();
    };
  }

  private static long hash(ProviderRequest r) {
    long h = 0xCBF29CE484222325L;
    StringBuilder sb = new StringBuilder(r.purpose().key());
    for (Block b : r.prefix()) sb.append('\u0000').append(b.text());
    for (Part p : r.content()) {
      sb.append('\u0001').append(p instanceof Part.Text t ? t.text() : ((Part.Image) p).base64());
    }
    for (byte b : sb.toString().getBytes(StandardCharsets.UTF_8)) {
      h ^= (b & 0xFF);
      h *= 0x100000001B3L;
    }
    return h;
  }
}
