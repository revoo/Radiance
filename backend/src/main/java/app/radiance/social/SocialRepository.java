package app.radiance.social;

import static app.radiance.persistence.Table.col;

import app.radiance.kernel.AirStatus;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.RenderStatus;
import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Posts, comments and reactions as the engine writes them. Feed and thread reads for R (aired and
 * rendered only, cursor-paged) are API read paths, added with the API in M7.
 */
public final class SocialRepository {

  static final Table<PostRow> POSTS =
      new Table<>(
          "post",
          List.of("id"),
          r ->
              new PostRow(
                  r.str("id"),
                  r.str("author_id"),
                  r.lng("created_at"),
                  r.enm("kind", PostRow.Kind.class),
                  r.enm("audience", PostRow.Audience.class),
                  r.enm("origin", ContentOrigin.class),
                  r.enm("air_status", AirStatus.class),
                  r.str("body"),
                  r.enm("render_status", RenderStatus.class),
                  r.str("brief_json"),
                  r.str("appraisal_json"),
                  r.str("media_id"),
                  r.str("headline_id"),
                  r.str("arc_id"),
                  r.str("event_id"),
                  (int) r.lng("importance"),
                  r.enm("fidelity", Fidelity.class),
                  r.str("reaction_counts_json"),
                  (int) r.lng("comment_count")),
          col("id", PostRow::id),
          col("author_id", PostRow::authorId),
          col("created_at", PostRow::createdAt),
          col("kind", PostRow::kind),
          col("audience", PostRow::audience),
          col("origin", PostRow::origin),
          col("air_status", PostRow::airStatus),
          col("body", PostRow::body),
          col("render_status", PostRow::renderStatus),
          col("brief_json", PostRow::briefJson),
          col("appraisal_json", PostRow::appraisalJson),
          col("media_id", PostRow::mediaId),
          col("headline_id", PostRow::headlineId),
          col("arc_id", PostRow::arcId),
          col("event_id", PostRow::eventId),
          col("importance", PostRow::importance),
          col("fidelity", PostRow::fidelity),
          col("reaction_counts_json", PostRow::reactionCountsJson),
          col("comment_count", PostRow::commentCount));

  static final Table<CommentRow> COMMENTS =
      new Table<>(
          "comment",
          List.of("id"),
          r ->
              new CommentRow(
                  r.str("id"),
                  r.str("post_id"),
                  r.str("parent_id"),
                  r.str("author_id"),
                  r.lng("created_at"),
                  r.enm("origin", ContentOrigin.class),
                  r.enm("air_status", AirStatus.class),
                  r.str("body"),
                  r.str("gist"),
                  r.enm("render_status", RenderStatus.class),
                  r.str("brief_json"),
                  r.str("appraisal_json"),
                  r.str("reaction_counts_json")),
          col("id", CommentRow::id),
          col("post_id", CommentRow::postId),
          col("parent_id", CommentRow::parentId),
          col("author_id", CommentRow::authorId),
          col("created_at", CommentRow::createdAt),
          col("origin", CommentRow::origin),
          col("air_status", CommentRow::airStatus),
          col("body", CommentRow::body),
          col("gist", CommentRow::gist),
          col("render_status", CommentRow::renderStatus),
          col("brief_json", CommentRow::briefJson),
          col("appraisal_json", CommentRow::appraisalJson),
          col("reaction_counts_json", CommentRow::reactionCountsJson));

  static final Table<ReactionRow> REACTIONS =
      new Table<>(
          "reaction",
          List.of("target_type", "target_id", "actor_id"),
          r ->
              new ReactionRow(
                  r.enm("target_type", ReactionRow.Target.class),
                  r.str("target_id"),
                  r.str("actor_id"),
                  r.enm("kind", ReactionRow.Kind.class),
                  r.lng("created_at"),
                  r.enm("air_status", AirStatus.class)),
          col("target_type", ReactionRow::targetType),
          col("target_id", ReactionRow::targetId),
          col("actor_id", ReactionRow::actorId),
          col("kind", ReactionRow::kind),
          col("created_at", ReactionRow::createdAt),
          col("air_status", ReactionRow::airStatus));

  private final Sql sql;

  public SocialRepository(Sql sql) {
    this.sql = sql;
  }

  public Optional<PostRow> post(String id) {
    return POSTS.one(sql, "id = ?", id);
  }

  public List<PostRow> scriptedPosts() {
    return POSTS.where(sql, "air_status = 'SCRIPTED' ORDER BY created_at, id");
  }

  public List<CommentRow> commentsOn(String postId) {
    return COMMENTS.where(sql, "post_id = ? ORDER BY created_at, id", postId);
  }

  public List<CommentRow> scriptedComments() {
    return COMMENTS.where(sql, "air_status = 'SCRIPTED' ORDER BY created_at, id");
  }

  /** Aired posts since {@code from}, oldest first (uses {@code ix_post_created}). */
  public List<PostRow> airedPostsSince(long from) {
    return POSTS.where(sql, "created_at >= ? AND air_status = 'AIRED' ORDER BY created_at, id", from);
  }

  /** Aired comments since {@code from}, oldest first (uses {@code ix_comment_created}). */
  public List<CommentRow> airedCommentsSince(long from) {
    return COMMENTS.where(sql, "created_at >= ? AND air_status = 'AIRED' ORDER BY created_at, id", from);
  }

  public List<ReactionRow> reactionsTo(ReactionRow.Target type, String targetId) {
    return REACTIONS.where(sql, "target_type = ? AND target_id = ?", type, targetId);
  }

  public List<ReactionRow> scriptedReactions() {
    return REACTIONS.where(sql, "air_status = 'SCRIPTED' ORDER BY created_at");
  }

  public UnitOfWork savePosts(UnitOfWork uow, Collection<PostRow> rows) {
    return POSTS.upsert(uow, rows);
  }

  /** Posts first: comments reference them, and replies reference their parents. */
  public UnitOfWork saveComments(UnitOfWork uow, Collection<CommentRow> rows) {
    return COMMENTS.upsert(uow, rows);
  }

  public UnitOfWork saveReactions(UnitOfWork uow, Collection<ReactionRow> rows) {
    return REACTIONS.upsert(uow, rows);
  }

  /** R takes a reaction back (SPEC §7, {@code DELETE /api/reactions}). */
  public UnitOfWork deleteReaction(
      UnitOfWork uow, ReactionRow.Target type, String targetId, String actorId) {
    return uow.add(
        "DELETE FROM reaction WHERE target_type = ? AND target_id = ? AND actor_id = ?",
        type,
        targetId,
        actorId);
  }
}
