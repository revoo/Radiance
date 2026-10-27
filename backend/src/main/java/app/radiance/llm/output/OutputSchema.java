package app.radiance.llm.output;

import app.radiance.kernel.Json;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Derives a JSON Schema from an output record (SPEC §5.10), in the subset most providers accept:
 * strings, numbers, booleans, enums, lists and nested records; every field required; no numeric or
 * length constraints; no extra properties. Schemas are cached per type.
 */
public final class OutputSchema {

  private static final Map<Class<?>, ObjectNode> CACHE = new ConcurrentHashMap<>();

  private OutputSchema() {}

  /** The schema for {@code type}. Callers must not mutate it. */
  public static ObjectNode of(Class<?> type) {
    return CACHE.computeIfAbsent(type, OutputSchema::record);
  }

  /** A name providers accept for the schema: letters, digits and underscores. */
  public static String name(Class<?> type) {
    return type.getSimpleName().replaceAll("[^A-Za-z0-9_]", "_");
  }

  private static ObjectNode record(Class<?> type) {
    if (!type.isRecord()) {
      throw new IllegalArgumentException("output schemas are derived from records only: " + type);
    }
    var node = Json.MAPPER.createObjectNode();
    node.put("type", "object");
    var props = node.putObject("properties");
    var required = node.putArray("required");
    for (RecordComponent c : type.getRecordComponents()) {
      String n = c.getName();
      if (n.equalsIgnoreCase("reasoning") || n.equalsIgnoreCase("rationale")) {
        throw new IllegalArgumentException(
            type.getSimpleName() + "." + n + ": no reasoning fields in output schemas (SPEC §5.10)");
      }
      props.set(n, forType(c.getGenericType(), type.getSimpleName() + "." + n));
      required.add(n);
    }
    node.put("additionalProperties", false);
    return node;
  }

  private static JsonNode forType(Type t, String where) {
    if (t instanceof ParameterizedType p && p.getRawType() == List.class) {
      var node = Json.MAPPER.createObjectNode();
      node.put("type", "array");
      node.set("items", forType(p.getActualTypeArguments()[0], where + "[]"));
      return node;
    }
    if (!(t instanceof Class<?> c)) {
      throw new IllegalArgumentException(where + ": unsupported type " + t);
    }
    var node = Json.MAPPER.createObjectNode();
    if (c == String.class) {
      node.put("type", "string");
    } else if (c == int.class || c == Integer.class || c == long.class || c == Long.class) {
      node.put("type", "integer");
    } else if (c == double.class || c == Double.class || c == float.class || c == Float.class) {
      node.put("type", "number");
    } else if (c == boolean.class || c == Boolean.class) {
      node.put("type", "boolean");
    } else if (c.isEnum()) {
      node.put("type", "string");
      var values = node.putArray("enum");
      for (Object e : c.getEnumConstants()) values.add(((Enum<?>) e).name());
    } else if (c.isRecord()) {
      return record(c);
    } else {
      throw new IllegalArgumentException(where + ": unsupported type " + c.getName());
    }
    return node;
  }
}
