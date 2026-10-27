package app.radiance.sim;

/**
 * A planned action past the end of the current script (SPEC §5.1). {@code kind} is open-ended
 * (REACT, COMMENT, REPLY, POST, DM, FRIEND_REQUEST, ARC_BEAT…) until M4 fixes the intent hierarchy.
 */
public record ScheduledIntentRow(
    String id,
    long dueAt,
    String actorId,
    String kind,
    String targetType,
    String targetId,
    String payloadJson,
    Status status,
    String causeEventId) {

  public enum Status {
    PENDING,
    DONE,
    CANCELLED
  }
}
