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
    long updatedAt,
    String pendingTagsJson) {

  /** A row with no impression tags waiting out their hysteresis. */
  public RelationshipRow(
      String fromId, String toId, Kind kind, double affinity, double trust, double attraction,
      double familiarity, double respect, double chemistry, String tagsJson, String summary,
      boolean connected, Long lastInteractionAt, long updatedAt) {
    this(fromId, toId, kind, affinity, trust, attraction, familiarity, respect, chemistry, tagsJson, summary,
        connected, lastInteractionAt, updatedAt, "{}");
  }


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
