package app.radiance.live;

import app.radiance.batch.ImageWork;
import app.radiance.content.ContentLibrary;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.Brief;
import app.radiance.sim.PhotoPrompts;
import app.radiance.sim.WorldEngine;
import java.util.List;

/** Pictures while R is online (SPEC section 1): a written post or DM gets its picture before, or just after, it lands. */
public final class OnlinePictures {

  private final Sql sql;
  private final WriterLane lane;
  private final WorldEngine engine;
  private final ContentLibrary lib;
  private final ImageWork images;

  public OnlinePictures(Sql sql, WriterLane lane, WorldEngine engine, ContentLibrary lib, ImageWork images) {
    this.engine = engine;
    this.lib = lib;
    this.sql = sql;
    this.lane = lane;
    this.images = images;
  }

  boolean post(String postId, boolean better) {
    var row = sql.queryOne("SELECT author_id, arc_id, brief_json, gist FROM post WHERE id = ? AND render_status = 'RENDERED'", List.of(postId),
        r -> new String[] {r.str("author_id"), r.isNull("arc_id") ? null : r.str("arc_id"), r.str("brief_json"), r.isNull("gist") ? null : r.str("gist")}).orElse(null);
    if (row == null) return false;
    // the prompt under the lock, the picture outside it: an image takes seconds, and the world keeps moving
    var photo = engine.locked(w -> PhotoPrompts.of(w, lib, postId, row[0], row[1], Brief.parse(row[2]), null, row[3]));
    return photo.isPresent() && images.online(photo.get(), better,
        media -> lane.run(() -> sql.commit(new UnitOfWork().add("UPDATE post SET media_id = ? WHERE id = ?", media.id(), postId))));
  }

  boolean dm(String messageId, boolean better) {
    var row = sql.queryOne("SELECT sender_id, brief_json, gist FROM dm_message WHERE id = ? AND render_status = 'RENDERED'", List.of(messageId),
        r -> new String[] {r.str("sender_id"), r.str("brief_json"), r.isNull("gist") ? null : r.str("gist")}).orElse(null);
    if (row == null) return false;
    var photo = engine.locked(w -> PhotoPrompts.of(w, lib, messageId, row[0], null, Brief.parse(row[1]), null, row[2]));
    return photo.isPresent() && images.online(photo.get(), better,
        media -> lane.run(() -> sql.commit(new UnitOfWork().add("UPDATE dm_message SET media_id = ? WHERE id = ?", media.id(), messageId))));
  }
}
