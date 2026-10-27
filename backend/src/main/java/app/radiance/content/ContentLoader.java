package app.radiance.content;

import app.radiance.content.Defs.ArcDef;
import app.radiance.content.Defs.ConstraintDef;
import app.radiance.content.Defs.EventDef;
import app.radiance.content.Defs.QuirkDef;
import app.radiance.content.Rules.EngagementRules;
import app.radiance.content.Rules.PacingRules;
import app.radiance.content.Rules.RelationshipRules;
import app.radiance.content.Rules.TemperamentRules;
import app.radiance.content.Rules.WeatherCondition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.PropertyNamingStrategy;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads content from {@code path → text} (paths relative to {@code content/}) and validates it as a
 * whole. Def folders hold one def per file or a list of defs. Every problem is collected before
 * failing, so one startup shows them all.
 */
public final class ContentLoader {

  static final YAMLMapper SNAKE = mapper(PropertyNamingStrategies.SNAKE_CASE);
  static final YAMLMapper KEBAB = mapper(PropertyNamingStrategies.KEBAB_CASE);

  private static YAMLMapper mapper(PropertyNamingStrategy naming) {
    return YAMLMapper.builder()
        .propertyNamingStrategy(naming)
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES) // on by default in Jackson 3; absent flags mean false
        .build();
  }

  private ContentLoader() {}

  public static ContentLibrary load(
      Map<String, String> files, List<ContentProperties.BlacklistEntry> blacklist) {
    var problems = new ArrayList<String>();
    var sorted = new TreeMap<>(files);

    var quirks = defs(sorted, "quirks/", QuirkDef.class, QuirkDef::id, problems);
    var events = defs(sorted, "events/", EventDef.class, EventDef::id, problems);
    var arcs = defs(sorted, "arcs/", ArcDef.class, ArcDef::id, problems);
    var constraints = defs(sorted, "constraints/", ConstraintDef.class, ConstraintDef::id, problems);

    var temperament = one(sorted, "rules/temperament.yaml", KEBAB, TemperamentRules.class, problems);
    Map<String, WeatherCondition> weather = one(sorted, "rules/weather.yaml", SNAKE,
        SNAKE.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, WeatherCondition.class),
        problems);
    var relationships = one(sorted, "rules/relationship-deltas.yaml", SNAKE, RelationshipRules.class, problems);
    var engagement = one(sorted, "rules/engagement.yaml", SNAKE, EngagementRules.class, problems);
    var pacing = one(sorted, "rules/pacing.yaml", SNAKE, PacingRules.class, problems);
    var roster = one(sorted, "cast/roster.yaml", KEBAB, Roster.class, problems);
    var tables = tables(sorted, problems);

    if (!problems.isEmpty()) throw new ContentException(problems);
    var library = new ContentLibrary(quirks, events, arcs, constraints, temperament,
        weather == null ? Map.of() : Map.copyOf(weather), relationships, engagement, pacing, roster,
        tables, List.copyOf(blacklist));
    problems.addAll(ContentValidator.validate(library));
    if (!problems.isEmpty()) throw new ContentException(problems);
    return library;
  }

  private static <T> Map<String, T> defs(Map<String, String> files, String folder, Class<T> type,
      Function<T, String> id, List<String> problems) {
    var out = new LinkedHashMap<String, T>();
    var where = new LinkedHashMap<String, String>();
    files.forEach((path, text) -> {
      if (!path.startsWith(folder) || !path.endsWith(".yaml")) return;
      try {
        JsonNode root = SNAKE.readTree(text);
        var nodes = new ArrayList<JsonNode>();
        if (root == null || root.isMissingNode() || root.isNull()) {
          problems.add(path + ": empty file");
          return;
        }
        if (root.isArray()) root.forEach(nodes::add);
        else nodes.add(root);
        for (JsonNode node : nodes) {
          T def = SNAKE.treeToValue(node, type);
          String key = id.apply(def);
          if (key == null || key.isBlank()) {
            problems.add(path + ": a def has no id");
          } else if (out.containsKey(key)) {
            problems.add(path + ": duplicate id '" + key + "' (also in " + where.get(key) + ")");
          } else {
            out.put(key, def);
            where.put(key, path);
          }
        }
      } catch (JacksonException e) {
        problems.add(path + ": " + e.getOriginalMessage() + location(e));
      }
    });
    return out;
  }

  /** {@code tables/<name>.yaml}: a list of rows (maps), drawn from by the engine and genesis. */
  @SuppressWarnings("unchecked")
  private static Map<String, List<Map<String, Object>>> tables(Map<String, String> files, List<String> problems) {
    var out = new java.util.TreeMap<String, List<Map<String, Object>>>();
    files.forEach((path, text) -> {
      if (!path.startsWith("tables/") || !path.endsWith(".yaml")) return;
      String name = path.substring("tables/".length(), path.length() - ".yaml".length());
      try {
        Object parsed = SNAKE.readValue(text, Object.class);
        if (!(parsed instanceof List<?> rows) || rows.isEmpty()
            || !rows.stream().allMatch(r -> r instanceof Map<?, ?>)) {
          problems.add(path + ": a table is a non-empty list of rows");
          return;
        }
        out.put(name, List.copyOf((List<Map<String, Object>>) parsed));
      } catch (JacksonException e) {
        problems.add(path + ": " + e.getOriginalMessage() + location(e));
      }
    });
    return Map.copyOf(out);
  }

  private static <T> T one(Map<String, String> files, String path, YAMLMapper mapper, Class<T> type,
      List<String> problems) {
    return one(files, path, mapper, mapper.getTypeFactory().constructType(type), problems);
  }

  private static <T> T one(Map<String, String> files, String path, YAMLMapper mapper, JavaType type,
      List<String> problems) {
    String text = files.get(path);
    if (text == null) {
      problems.add(path + ": missing");
      return null;
    }
    try {
      return mapper.readValue(text, type);
    } catch (JacksonException e) {
      problems.add(path + ": " + e.getOriginalMessage() + location(e));
      return null;
    }
  }

  private static String location(JacksonException e) {
    var loc = e.getLocation();
    String path = e.getPathReference();
    return (loc != null && loc.getLineNr() > 0 ? " (line " + loc.getLineNr() + ")" : "")
        + (path != null && !path.isBlank() ? " at " + path : "");
  }
}
