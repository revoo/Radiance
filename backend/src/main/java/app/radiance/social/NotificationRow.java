package app.radiance.social;

/**
 * A notification for R, created when its item airs (SPEC §5.14).
 *
 * @param kind REACTION, COMMENT, REPLY, MENTION, DM, FRIEND_REQUEST, FRIEND_ACCEPT or BIRTHDAY
 */
public record NotificationRow(
    String id, String kind, String actorId, String refType, String refId, long createdAt, Long readAt) {}
