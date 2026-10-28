package app.radiance.sim.model;

import java.util.Map;

/**
 * Something an actor means to do later (SPEC §5.1): reply to a comment, follow up on a beat. Due
 * intents fire inside planning; ones past the end of the script are kept in {@code scheduled_intent}.
 *
 * @param kind REACT, COMMENT, REPLY, POST, DM, FRIEND_REQUEST or ARC_BEAT
 */
public record Intent(String id, long dueAt, String actorId, String kind, String targetType, String targetId,
    Map<String, Object> payload, String causeEventId) {

  public Intent {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }
}
