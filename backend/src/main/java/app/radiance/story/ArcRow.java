package app.radiance.story;

import app.radiance.kernel.Visibility;

/** An ongoing storyline instance: committed state (SPEC §5.5). */
public record ArcRow(
    String id,
    String defId,
    Status status,
    String phase,
    Visibility visibility,
    long startedAt,
    long phaseEnteredAt,
    Long nextTransitionAt,
    Long lastBeatAt,
    String dataJson,
    String summary,
    Long endedAt,
    String endEventId) {

  public enum Status {
    ACTIVE,
    RESOLVED,
    ABANDONED
  }

  public record Participant(String arcId, String actorId, String role) {}
}
