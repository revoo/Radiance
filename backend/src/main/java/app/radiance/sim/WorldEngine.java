package app.radiance.sim;

import app.radiance.content.ContentLibrary;
import app.radiance.kernel.AirStatus;
import app.radiance.kernel.Json;
import app.radiance.memory.MemoryRepository;
import app.radiance.messenger.DmRepository;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.model.SimWorld;
import app.radiance.social.NotificationRepository;
import app.radiance.social.SocialRepository;
import app.radiance.story.EventRepository;
import app.radiance.weather.WeatherBook;
import app.radiance.weather.WeatherRepository;
import java.util.ArrayList;
import java.util.List;

/**
 * The only way committed time moves (SPEC §5.1, P3): {@link #advanceTo} airs every scripted item due
 * by then, an hour of world time per unit of work, and moves {@code world.committed_until}. Keeps the
 * committed world in memory so live play never re-reads it (SPEC §4.4).
 */
public final class WorldEngine {

  /** What one advance did. */
  public record Report(int aired, int dropped, int memories, int notifications, List<String> dropReasons,
      long rowsWritten) {}

  static final long HOUR = 3_600_000L;

  private final Sql sql;
  private final WriterLane lane;
  private final ContentLibrary lib;
  private final Knobs knobs;
  private final ModelStore store;
  private SimWorld world;

  public WorldEngine(Sql sql, WriterLane lane, ContentLibrary lib, Knobs knobs) {
    this.sql = sql;
    this.lane = lane;
    this.lib = lib;
    this.knobs = knobs;
    this.store = new ModelStore(sql, lib);
  }

  /** The committed world, loaded on first use (and again after {@link #reload}, e.g. a new lease). */
  public SimWorld world() {
    if (world == null) world = store.load();
    return world;
  }

  public void reload() {
    world = null;
  }

  public Knobs knobs() {
    return knobs;
  }

  public ContentLibrary lib() {
    return lib;
  }

  /** The weather airing re-checks against: whatever the latest fetch stored. */
  public WeatherBook weather(long from, long to) {
    return new WeatherBook(world().zone, lib.weather())
        .putAll(new WeatherRepository(sql).between(from - from % HOUR - 8 * 24 * HOUR, to + HOUR));
  }

  public Report advanceTo(long t) {
    var w = world();
    if (t <= w.time) return new Report(0, 0, 0, 0, List.of(), 0);
    int aired = 0, dropped = 0, memories = 0, notes = 0;
    long rows = 0;
    var reasons = new ArrayList<String>();
    var weather = weather(w.time, t);
    while (w.time < t) {
      long next = Math.min(t, w.time - Math.floorMod(w.time, HOUR) + HOUR);
      var script = load(next);
      var ctx = new Airing.Ctx(lib, knobs, weather, true, this::lookup);
      var out = Airing.air(w, script.entries(), ctx);
      w.time = next;
      var uow = new UnitOfWork();
      statuses(uow, out);
      touched(uow, w, out);
      new MemoryRepository(sql).save(uow, out.memories);
      new NotificationRepository(sql).save(uow, out.notifications);
      store.write(uow, w);
      rows += uow.size() + out.memories.size() + out.notifications.size();
      lane.run(() -> sql.commit(uow));
      aired += out.aired;
      dropped += out.dropped;
      memories += out.memories.size();
      notes += out.notifications.size();
      reasons.addAll(out.dropReasons);
      trim(w);
    }
    return new Report(aired, dropped, memories, notes, reasons, rows);
  }

  private Script load(long until) {
    var s = new Script();
    s.events.addAll(new EventRepository(sql).scriptedUntil(until));
    var social = new SocialRepository(sql);
    s.posts.addAll(social.scriptedPostsUntil(until));
    s.comments.addAll(social.scriptedCommentsUntil(until));
    s.reactions.addAll(social.scriptedReactionsUntil(until));
    s.dms.addAll(new DmRepository(sql).scriptedUntil(until));
    return s;
  }

  private AirStatus lookup(Script.Type type, String id) {
    var social = new SocialRepository(sql);
    return switch (type) {
      case EVENT -> new EventRepository(sql).find(id).map(e -> e.airStatus()).orElse(null);
      case POST -> social.post(id).map(p -> p.airStatus()).orElse(null);
      case COMMENT -> social.comment(id).map(c -> c.airStatus()).orElse(null);
      default -> null;
    };
  }

  private static void statuses(UnitOfWork uow, Airing.Out out) {
    for (var e : out.statuses.entrySet()) {
      int i = e.getKey().indexOf(':');
      String type = e.getKey().substring(0, i);
      String id = e.getKey().substring(i + 1);
      String status = e.getValue().name();
      // facts the prose introduced become canon when their item airs, and void if it's dropped (SPEC P2)
      if (!type.equals("REACTION") && !type.equals("EVENT")) {
        uow.add("UPDATE fact SET status = ? WHERE origin_ref = ? AND status = 'PENDING'",
            e.getValue() == AirStatus.AIRED ? "ACTIVE" : "VOID", id);
      }
      switch (Script.Type.valueOf(type)) {
        case EVENT -> uow.add("UPDATE event SET air_status = ? WHERE id = ?", status, id);
        case POST -> {
          uow.add("UPDATE post SET air_status = ? WHERE id = ?", status, id);
          if (e.getValue() == AirStatus.DROPPED) uow.add("UPDATE post SET render_status = 'DROPPED' WHERE id = ?", id);
        }
        case COMMENT -> {
          uow.add("UPDATE comment SET air_status = ? WHERE id = ?", status, id);
          // aired, rendered comments only (SPEC §4.3)
          uow.add("UPDATE post SET comment_count = (SELECT COUNT(*) FROM comment c WHERE c.post_id = post.id"
              + " AND c.air_status = 'AIRED' AND c.render_status = 'RENDERED') WHERE id = (SELECT post_id FROM comment"
              + " WHERE id = ?)", id);
        }
        case DM -> uow.add("UPDATE dm_message SET air_status = ? WHERE id = ?", status, id);
        case REACTION -> {
          var k = id.split(":", 3);
          uow.add("UPDATE reaction SET air_status = ? WHERE target_type = ? AND target_id = ? AND actor_id = ?", status,
              k[0], k[1], k[2]);
        }
      }
    }
  }

  private static void touched(UnitOfWork uow, SimWorld w, Airing.Out out) {
    for (String id : out.touchedPosts) {
      var item = w.items.get(id);
      if (item != null) uow.add("UPDATE post SET reaction_counts_json = ? WHERE id = ?", Json.write(item.reactionCounts), id);
    }
  }

  /** Keeps a few days of items in memory; older ones are looked up in the DB when needed. */
  private static void trim(SimWorld w) {
    long cutoff = w.time - ModelStore.ITEM_WINDOW;
    w.items.values().removeIf(i -> i.time < cutoff);
  }
}
