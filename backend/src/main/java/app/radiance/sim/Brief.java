package app.radiance.sim;

import app.radiance.kernel.Json;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The engine's brief for one scripted item (SPEC §5.10), stored as {@code brief_json}: what the
 * renderer writes from, and the structure airing and the engagement model read back.
 *
 * @param text the beat in plain words ("hyping a 'steal' he found online; friends warn him")
 * @param toward the actor a comment, reply or DM addresses
 * @param interaction the rule-table key airing applies ({@code supportive_comment}, {@code debate_exchange})
 * @param extra anything else the renderer needs: participants, entity ids, the headline's stance
 */
public record Brief(String defId, String category, String text, String stance, String toward, String interaction,
    List<String> topics, double controversy, double drama, Map<String, Object> extra) {

  public Brief {
    topics = topics == null ? List.of() : List.copyOf(topics);
    extra = extra == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(extra));
  }

  public static final Brief NONE = new Brief(null, null, null, null, null, null, List.of(), 0, 0, Map.of());

  public String json() {
    return Json.write(this);
  }

  /** Lenient: rows written by other paths (R's own posts) may have no brief. */
  public static Brief parse(String json) {
    if (json == null || json.isBlank()) return NONE;
    JsonNode n = Json.tree(json);
    var topics = new java.util.ArrayList<String>();
    n.path("topics").forEach(t -> topics.add(t.asString()));
    @SuppressWarnings("unchecked")
    Map<String, Object> extra = n.has("extra") ? Json.MAPPER.convertValue(n.get("extra"), Map.class) : Map.of();
    return new Brief(text(n, "defId"), text(n, "category"), text(n, "text"), text(n, "stance"), text(n, "toward"),
        text(n, "interaction"), topics, n.path("controversy").asDouble(0), n.path("drama").asDouble(0), extra);
  }

  private static String text(JsonNode n, String field) {
    var v = n.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }
}
