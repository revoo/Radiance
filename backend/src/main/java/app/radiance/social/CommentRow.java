package app.radiance.social;

import app.radiance.kernel.AirStatus;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.RenderStatus;

/** A comment or a reply ({@code parentId} set); {@code createdAt} is its air time. */
public record CommentRow(
    String id,
    String postId,
    String parentId,
    String authorId,
    long createdAt,
    ContentOrigin origin,
    AirStatus airStatus,
    String body,
    String gist,
    RenderStatus renderStatus,
    String briefJson,
    String appraisalJson,
    String reactionCountsJson) {}
