package app.radiance.llm.output;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns model text into a typed record without trusting the provider (SPEC §5.10): strips code fences
 * and surrounding prose, extracts the JSON, validates it against the schema (enum values compared
 * case-insensitively and normalized), then binds it.
 */
public final class OutputParser {

  private static final Pattern FENCE =
      Pattern.compile("```(?:json|JSON)?\\s*(.*?)```", Pattern.DOTALL);

  private static final JsonMapper LENIENT =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private OutputParser() {}

  public static <T> T parse(String text, Class<T> type) throws InvalidOutputException {
    if (text == null || text.isBlank()) throw new InvalidOutputException(List.of("empty output"));
    JsonNode node = extract(text);
    var errors = new ArrayList<String>();
    validate(node, OutputSchema.of(type), "$", errors);
    if (!errors.isEmpty()) throw new InvalidOutputException(errors);
    try {
      return LENIENT.treeToValue(node, type);
    } catch (JacksonException e) {
      throw new InvalidOutputException(List.of("does not bind to " + type.getSimpleName() + ": "
          + e.getOriginalMessage()));
    }
  }

  static JsonNode extract(String text) throws InvalidOutputException {
    var candidates = new ArrayList<String>();
    var fence = FENCE.matcher(text);
    if (fence.find()) candidates.add(fence.group(1));
    int open = text.indexOf('{');
    int close = text.lastIndexOf('}');
    if (open >= 0 && close > open) candidates.add(text.substring(open, close + 1));
    candidates.add(text);
    for (String c : candidates) {
      try {
        JsonNode node = LENIENT.readTree(c.strip());
        if (node != null && node.isObject()) return node;
      } catch (JacksonException ignored) {
        // try the next candidate
      }
    }
    throw new InvalidOutputException(List.of("no JSON object found in the output"));
  }

  private static void validate(JsonNode value, JsonNode schema, String path, List<String> errors) {
    String type = schema.path("type").asString();
    switch (type) {
      case "object" -> {
        if (!(value instanceof ObjectNode obj)) {
          errors.add(path + ": expected an object");
          return;
        }
        var props = schema.path("properties");
        for (JsonNode req : schema.path("required")) {
          String name = req.asString();
          JsonNode v = obj.get(name);
          if (v == null || v.isNull()) {
            errors.add(path + "." + name + ": missing");
          } else {
            validate(v, props.get(name), path + "." + name, errors);
            if (v.isString() && props.get(name).has("enum")) {
              normalizeEnum(obj, name, props.get(name), path, errors);
            }
          }
        }
      }
      case "array" -> {
        if (!(value instanceof ArrayNode arr)) {
          errors.add(path + ": expected an array");
          return;
        }
        var items = schema.get("items");
        for (int i = 0; i < arr.size(); i++) {
          JsonNode v = arr.get(i);
          if (v == null || v.isNull()) {
            errors.add(path + "[" + i + "]: null");
            continue;
          }
          validate(v, items, path + "[" + i + "]", errors);
          if (v.isString() && items.has("enum")) {
            String canonical = canonicalEnum(v.asString(), items);
            if (canonical == null) errors.add(path + "[" + i + "]: " + enumError(v, items));
            else arr.set(i, arr.stringNode(canonical));
          }
        }
      }
      case "string" -> {
        if (!value.isString()) errors.add(path + ": expected a string");
      }
      case "integer" -> {
        if (!(value.isIntegralNumber()
            || (value.isNumber() && value.asDouble() == Math.rint(value.asDouble())))) {
          errors.add(path + ": expected an integer");
        }
      }
      case "number" -> {
        if (!value.isNumber()) errors.add(path + ": expected a number");
      }
      case "boolean" -> {
        if (!value.isBoolean()) errors.add(path + ": expected true or false");
      }
      default -> errors.add(path + ": unsupported schema type " + type);
    }
  }

  private static void normalizeEnum(
      ObjectNode obj, String name, JsonNode schema, String path, List<String> errors) {
    JsonNode v = obj.get(name);
    String canonical = canonicalEnum(v.asString(), schema);
    if (canonical == null) errors.add(path + "." + name + ": " + enumError(v, schema));
    else obj.put(name, canonical);
  }

  private static String canonicalEnum(String given, JsonNode schema) {
    for (JsonNode e : schema.get("enum")) {
      if (e.asString().equalsIgnoreCase(given.strip())) return e.asString();
    }
    return null;
  }

  private static String enumError(JsonNode v, JsonNode schema) {
    return "\"" + v.asString() + "\" is not one of " + schema.get("enum");
  }
}
