package app.radiance.social;

import app.radiance.kernel.AirStatus;

/** One actor's reaction to a post or comment; at most one per actor and target. */
public record ReactionRow(
    Target targetType, String targetId, String actorId, Kind kind, long createdAt, AirStatus airStatus) {

  public enum Target {
    POST,
    COMMENT
  }

  public enum Kind {
    LIKE,
    LOVE,
    CARE,
    HAHA,
    WOW,
    SAD,
    ANGRY
  }
}
