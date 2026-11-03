package app.radiance.media;

import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Ulid;
import app.radiance.kernel.WallClock;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import java.io.IOException;
import java.util.Optional;

/**
 * R's photos (SPEC §5.13, ADR 0021): the browser resizes them, then sends both sizes here. Nothing is trusted: the type comes
 * from the bytes, and each size has a cap. An upload is only usable once {@link #complete} has seen both.
 */
public final class Uploads {

  public static final int MAX_FULL = 8 * 1024 * 1024;
  public static final int MAX_THUMB = 1024 * 1024;

  /** Something the client should hear about: a bad type, a size over the cap, a missing part. */
  public static final class Rejected extends RuntimeException {
    public Rejected(String message) {
      super(message);
    }
  }

  private final Sql sql;
  private final WriterLane lane;
  private final MediaStore store;
  private final WallClock clock;

  public Uploads(Sql sql, WriterLane lane, MediaStore store, WallClock clock) {
    this.sql = sql;
    this.lane = lane;
    this.store = store;
    this.clock = clock;
  }

  public MediaRow start(String mime, long bytes, Integer width, Integer height) {
    if (mime == null || !ImageTypes.UPLOADABLE.contains(mime)) throw new Rejected("Photos only: JPEG, PNG or WebP");
    if (bytes <= 0 || bytes > MAX_FULL) throw new Rejected("That photo is too large");
    long now = clock.nowMs();
    String id = Ulid.next(now, RandomSource.seeded(System.nanoTime()).derive("upload"));
    var row = new MediaRow(id, "PHOTO", "UPLOAD", null, null, mime, width, height, bytes, null, null, null, null,
        MediaRow.PENDING, null, now);
    lane.run(() -> sql.commit(new MediaRepository(sql).save(new UnitOfWork(), row)));
    return row;
  }

  /** Stores one size of a pending upload. */
  public void receive(String id, boolean thumb, byte[] bytes) throws IOException, InterruptedException {
    var row = pending(id);
    if (bytes.length == 0 || bytes.length > (thumb ? MAX_THUMB : MAX_FULL)) throw new Rejected("That photo is too large");
    if (ImageTypes.sniff(bytes).isEmpty()) throw new Rejected("That file isn't a photo");
    store.put(key(row.id(), thumb), bytes);
  }

  /** Both sizes are in: the upload can be used. The vision appraisal runs after this. */
  public MediaRow complete(String id) throws IOException, InterruptedException {
    var row = pending(id);
    var full = store.get(key(id, false)).orElseThrow(() -> new Rejected("The photo didn't arrive"));
    store.get(key(id, true)).orElseThrow(() -> new Rejected("The thumbnail didn't arrive"));
    var type = ImageTypes.sniff(full).orElseThrow(() -> new Rejected("That file isn't a photo"));
    var ready = row.ready(key(id, false), key(id, true), type, full.length);
    lane.run(() -> sql.commit(new MediaRepository(sql).save(new UnitOfWork(), ready)));
    return ready;
  }

  private MediaRow pending(String id) {
    Optional<MediaRow> row = new MediaRepository(sql).byId(id);
    if (row.isEmpty() || !"UPLOAD".equals(row.get().origin()) || !MediaRow.PENDING.equals(row.get().status())) {
      throw new Rejected("No such upload");
    }
    return row.get();
  }

  static String key(String id, boolean thumb) {
    return "uploads/" + id + (thumb ? "-thumb" : "-full");
  }
}
