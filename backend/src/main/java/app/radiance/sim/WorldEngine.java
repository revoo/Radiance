package app.radiance.sim;

import app.radiance.content.ContentLibrary;
import app.radiance.kernel.AirStatus;
import app.radiance.kernel.Json;
import app.radiance.memory.MemoryRepository;
import app.radiance.messenger.DmRepository;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.model.Item;
import app.radiance.sim.model.SimWorld;
import app.radiance.social.NotificationRepository;
import app.radiance.social.NotificationRow;
import app.radiance.social.SocialRepository;
import app.radiance.story.EventRepository;
import app.radiance.weather.WeatherBook;
import app.radiance.weather.WeatherRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The only way committed time moves (SPEC §5.1, P3): {@link #advanceTo} airs every scripted item due
 * by then, an hour of world time per unit of work, and moves {@code world.committed_until}. Keeps the
 * committed world in memory so live play never re-reads it (SPEC §4.4).
 */
public final class WorldEngine {

  /**
   * What one advance did. {@code airedKeys} are {@code TYPE:id} of everything that aired, in air order;
   * {@code notes} are the notifications it created, for live play's stream.
   */
  public record Report(int aired, int dropped, int memories, int notifications, List<String> dropReasons,
      long rowsWritten, List<String> airedKeys, List<String> droppedKeys, List<NotificationRow> notes) {}

  static final long HOUR = 3_600_000L;

  private final Sql sql;
  private final WriterLane lane;
  private final ContentLibrary lib;
  private final Knobs knobs;
  private final ModelStore store;
  private SimWorld world;
  // live play, the in-process batch and R's actions all touch the committed world: one at a time
  private final ReentrantLock lock = new ReentrantLock();
  private final List<Consumer<Report>> listeners = new CopyOnWriteArrayList<>();

  public WorldEngine(Sql sql, WriterLane lane, ContentLibrary lib, Knobs knobs) {
    this.sql = sql;
    this.lane = lane;
    this.lib = lib;
    this.knobs = knobs;
    this.store = new ModelStore(sql, lib);
  }

  /** The committed world, loaded on first use (and again after {@link #reload}, e.g. a new lease). */
  public SimWorld world() {
    lock.lock();
    try {
      if (world == null) world = store.load();
      return world;
    } finally {
      lock.unlock();
    }
  }

  public void reload() {
    lock.lock();
    try {
      world = null;
    } finally {
      lock.unlock();
    }
  }

  /** Runs {@code f} on the committed world with nobody else airing or mutating it. */
  public <T> T locked(Function<SimWorld, T> f) {
    lock.lock();
    try {
      return f.apply(world());
    } finally {
      lock.unlock();
    }
  }

  /**
   * Called after every advance that aired or dropped something, while the world is still locked: keep
   * it quick, and never wait on another thread that needs the engine.
   */
  public void onAired(Consumer<Report> listener) {
    listeners.add(listener);
  }

  /** A committed post or comment by id: in memory, or loaded back from the database. */
  public Item item(String id) {
    return locked(w -> {
      var item = w.items.get(id);
      return item != null ? item : store.fetch(w, id);
    });
  }

  /**
   * {@link #advanceTo}, unless someone else (an in-process batch's airing) holds the world for longer
   * than {@code waitMs}: then nothing, and the caller reads what has aired so far.
   */
  public Optional<Report> tryAdvanceTo(long t, long waitMs) {
    try {
      if (!lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
    try {
      return Optional.of(advanceTo(t));
    } finally {
      lock.unlock();
    }
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
    lock.lock();
    try {
      var r = advance(t);
      if (!r.airedKeys().isEmpty() || !r.droppedKeys().isEmpty()) listeners.forEach(l -> l.accept(r));
      return r;
    } finally {
      lock.unlock();
    }
  }

  private Report advance(long t) {
    var w = world();
    if (t <= w.time) return new Report(0, 0, 0, 0, List.of(), 0, List.of(), List.of(), List.of());
    int aired = 0, dropped = 0, memories = 0, notes = 0;
    long rows = 0;
    var reasons = new ArrayList<String>();
    var airedKeys = new ArrayList<String>();
    var droppedKeys = new ArrayList<String>();
    var created = new ArrayList<NotificationRow>();
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
      out.statuses.forEach((key, status) -> (status == AirStatus.AIRED ? airedKeys : droppedKeys).add(key));
      created.addAll(out.notifications);
      trim(w);
    }
    return new Report(aired, dropped, memories, notes, reasons, rows, airedKeys, droppedKeys, created);
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
