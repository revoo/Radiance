package app.radiance.kernel;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The one JSON mapper for domain and persistence code (Jackson 3; exceptions are unchecked). */
public final class Json {

  public static final JsonMapper MAPPER = JsonMapper.builder().build();

  private Json() {}

  public static String write(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  public static JsonNode tree(String json) {
    return MAPPER.readTree(json);
  }

  public static <T> T read(String json, Class<T> type) {
    return MAPPER.readValue(json, type);
  }
}
