package app.radiance.sim;

import app.radiance.kernel.Json;
import java.util.List;
import java.util.Map;

/**
 * What a scripted event records in {@code payload_json} (SPEC §4.3): its participants and the effects
 * airing applies, so airing never re-plans anything — it re-checks and applies.
 *
 * @param participants role → actor id ({@code actor}, {@code with}, {@code target}, or an arc's roles)
 * @param effects the def's effects block; {@code "<who> -> <whom>"} keys name roles
 * @param arc the arc change this event makes, if any
 * @param entity a new entity the event creates (a car from {@code fixer_upper_cars})
 * @param learned knowledge that spreads when it airs: {@code [subjectType, subjectId, actorId]}
 * @param weather a named condition the event needs; airing re-checks it against the latest data
 * @param text the beat or def description in plain words
 */
public record EventPayload(
    String category, Map<String, String> participants, Map<String, Object> effects, ArcChange arc,
    NewThing entity, List<List<String>> learned, String weather, String text) {

  public EventPayload {
    participants = participants == null ? Map.of()
        : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(participants));
    effects = effects == null ? Map.of() : effects;
    learned = learned == null ? List.of() : List.copyOf(learned);
  }

  /**
   * @param kind START, TRANSITION or BEAT
   * @param status the arc's status after the change: ACTIVE, RESOLVED or ABANDONED
   * @param knowers who learns about a PRIVATE or SECRET phase when it airs
   */
  public record ArcChange(
      String arcId, String defId, String kind, String fromPhase, String toPhase, String visibility,
      Long nextTransitionAt, String status, List<String> knowers, Map<String, String> roles,
      Map<String, Object> data, String summary) {

    public ArcChange {
      knowers = knowers == null ? List.of() : List.copyOf(knowers);
      roles = roles == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(roles));
      data = data == null ? Map.of() : data;
    }
  }

  public record NewThing(String id, String ownerId, String kind, String name, String visualDescription, long imageSeed,
      Map<String, Object> attributes) {}

  public String json() {
    return Json.write(this);
  }

  public static EventPayload parse(String json) {
    return Json.read(json, EventPayload.class);
  }
}
