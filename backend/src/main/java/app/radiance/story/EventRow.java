package app.radiance.story;

import app.radiance.kernel.AirStatus;
import app.radiance.kernel.Visibility;

/** One entry in the append-only event log (SPEC §4.3). */
public record EventRow(
    String id,
    long worldTime,
    String type,
    String defId,
    String actorId,
    String targetId,
    String arcId,
    int importance,
    Visibility visibility,
    Source source,
    AirStatus airStatus,
    String payloadJson,
    String recapLine) {

  public EventRow withSource(Source s) {
    return new EventRow(id, worldTime, type, defId, actorId, targetId, arcId, importance, visibility, s, airStatus, payloadJson, recapLine);
  }

  public enum Source {
    STORYTELLER,
    ARC,
    ROUTINE,
    NEWS,
    CALENDAR,
    USER,
    DIRECTOR
  }
}
