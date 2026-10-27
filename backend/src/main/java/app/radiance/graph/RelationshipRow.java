package app.radiance.graph;

/** How {@code fromId} sees {@code toId} (SPEC §5.3). Directed and sparse. */
public record RelationshipRow(
    String fromId,
    String toId,
    Kind kind,
    double affinity,
    double trust,
    double attraction,
    double familiarity,
    double respect,
    double chemistry,
    String tagsJson,
    String summary,
    boolean connected,
    Long lastInteractionAt,
    long updatedAt) {

  public enum Kind {
    STRANGER,
    ACQUAINTANCE,
    FRIEND,
    CLOSE_FRIEND,
    FAMILY,
    COWORKER,
    PARTNER,
    EX,
    RIVAL
  }
}
