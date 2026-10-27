package app.radiance.social;

import app.radiance.kernel.AirStatus;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.RenderStatus;

/** A post; {@code createdAt} is its air time (SPEC §4.1). */
public record PostRow(
    String id,
    String authorId,
    long createdAt,
    Kind kind,
    Audience audience,
    ContentOrigin origin,
    AirStatus airStatus,
    String body,
    RenderStatus renderStatus,
    String briefJson,
    String appraisalJson,
    String mediaId,
    String headlineId,
    String arcId,
    String eventId,
    int importance,
    Fidelity fidelity,
    String reactionCountsJson,
    int commentCount) {

  public enum Kind {
    STATUS,
    PHOTO,
    LINK,
    LIFE_EVENT,
    CHECKIN
  }

  public enum Audience {
    FRIENDS,
    PUBLIC
  }
}
