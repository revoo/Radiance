package app.radiance.live;

import app.radiance.content.ContentLibrary;
import app.radiance.content.Durations;
import app.radiance.jobs.JobKinds;
import app.radiance.jobs.JobRepository;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Ulid;
import app.radiance.kernel.WallClock;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.Priority;
import app.radiance.narrative.Renderer;
import app.radiance.persistence.LeaseHolder;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.Brief;
import app.radiance.sim.Script;
import app.radiance.sim.UserActions;
import app.radiance.sim.WorldEngine;
import app.radiance.sim.model.Item;
import app.radiance.social.ReactionRow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live play (SPEC §5.1): R's session, and the real-time loop that runs while it lasts. The loop sleeps
 * until the next due release or command; each turn it airs what the script has due, lets the
 * {@link Pacer} release responses aimed at R (a typing indicator stands in for a render that's late),
 * delivers notifications within the Pacer's limits and surfaces a hook after a quiet stretch. R's
 * actions are written and aired at once; their responses are planned in two phases (reflex, then
 * considered after the appraisal) and rendered on virtual threads, never through the job queue.
 * Everything that reaches R goes out as a {@link LiveEvent} for the stream.
 */
public final class LivePlay {

  /** Something for the SPA's stream (SPEC §7 {@code GET /api/stream}). */
  public record LiveEvent(String name, Map<String, Object> data) {}

  /** What a heartbeat answers. */
  public record Status(long worldTime, long scriptedUntil, boolean backfilling, boolean sessionActive) {}

  /** A typing indicator on screen: "Sarah is writing a comment…". */
  public record Typing(String key, String postId, String actorId, String name, long since) {}

  /** The background backfill that runs when no script covers now (the batch, in-process). */
  public interface Backfill {
    boolean running();

    void start();
  }

  /** The pacing monitor's view (SPEC §12 M7: a basic one; the full one is the Director's, M13). */
  public record Monitor(String sessionId, Long sessionStartedAt, Long lastHeartbeat, List<Typing> typing,
      Pacer.Snapshot pacer) {}

  private static final Logger log = LoggerFactory.getLogger(LivePlay.class);
  private static final long AIR_EVERY = 10_000;

  private final Sql sql;
  private final WriterLane lane;
  private final WorldEngine engine;
  private final UserActions actions;
  private final Appraiser appraiser;
  private final LlmGateway gateway;
  private final ContentLibrary lib;
  private final WallClock clock;
  private final LeaseHolder lease;
  private final Backfill backfill;
  private final String networkName;
  private final boolean softening;
  private final long idleTimeout;
  private final double coldShoulderRate;
  private final List<Consumer<LiveEvent>> listeners = new CopyOnWriteArrayList<>();
  private final ConcurrentLinkedQueue<WorldEngine.Report> aired = new ConcurrentLinkedQueue<>();
  private final LinkedBlockingQueue<Boolean> wake = new LinkedBlockingQueue<>();
  private final Map<String, ReentrantLock> renderLocks = new ConcurrentHashMap<>();

  // guarded by this
  private String sessionId;
  private long sessionStart, lastBeat, lastAir, lastProgress;
  private Pacer pacer;
  private final Map<String, Slot> typing = new LinkedHashMap<>();
  private final List<DmSlot> dmSlots = new ArrayList<>();
  private long crushAt = Long.MAX_VALUE;
  private RandomSource sessionRng;
  private Thread loop;
  private boolean manual;
  private volatile boolean stopped;

  public LivePlay(Sql sql, WriterLane lane, WorldEngine engine, UserActions actions, Appraiser appraiser,
      LlmGateway gateway, WallClock clock, LeaseHolder lease, Backfill backfill, String networkName, boolean softening,
      Duration idleTimeout) {
    this(sql, lane, engine, actions, appraiser, gateway, clock, lease, backfill, networkName, softening, idleTimeout, 1.0);
  }

  public LivePlay(Sql sql, WriterLane lane, WorldEngine engine, UserActions actions, Appraiser appraiser,
      LlmGateway gateway, WallClock clock, LeaseHolder lease, Backfill backfill, String networkName, boolean softening,
      Duration idleTimeout, double coldShoulderRate) {
    this.coldShoulderRate = coldShoulderRate;
    this.sql = sql;
    this.lane = lane;
    this.engine = engine;
    this.actions = actions;
    this.appraiser = appraiser;
    this.gateway = gateway;
    this.lib = engine.lib();
    this.clock = clock;
    this.lease = lease;
    this.backfill = backfill;
    this.networkName = networkName;
    this.softening = softening;
    this.idleTimeout = idleTimeout.toMillis();
    engine.onAired(r -> {
      aired.add(r);
      wake.offer(true);
    });
  }

  /** For tests: no loop thread; the test calls {@link #tick} on its own clock. */
  synchronized LivePlay manualLoop() {
    manual = true;
    return this;
  }

  public void addListener(Consumer<LiveEvent> l) {
    listeners.add(l);
  }

  public void removeListener(Consumer<LiveEvent> l) {
    listeners.remove(l);
  }

  // ------------------------------------------------------------------------------------- sessions

  /** World time now: wall time plus the world's offset (SPEC §5.1 clock). */
  public long now() {
    long offset = sql.queryOne("SELECT time_offset_ms FROM world", List.of(), r -> r.lng("time_offset_ms")).orElse(0L);
    return clock.nowMs() + offset;
  }

  /**
   * Every API request of R's touches the session. The first one of a session airs everything due
   * before anything is read; if no script covers now, a background backfill starts and streams in.
   */
  public void touch() {
    if (!lease.isHeld() || !onboarded()) return;
    boolean fresh;
    synchronized (this) {
      long wall = clock.nowMs();
      fresh = sessionId == null || wall - lastBeat > idleTimeout;
      if (fresh) start(wall);
      lastBeat = wall;
    }
    if (fresh) {
      long t = now();
      engine.tryAdvanceTo(t, 300);
      engine.locked(w -> w.lastSessionAt = t);
      if (scriptedUntil() < t && !backfill.running()) {
        log.info("No script covers now: backfilling in the background");
        backfill.start();
      }
    }
  }

  public Status heartbeat() {
    touch();
    synchronized (this) {
      if (sessionId != null) {
        long wall = clock.nowMs();
        lane.run(() -> sql.commit(new UnitOfWork().add("UPDATE session_log SET ended_at = ? WHERE id = ?", wall, sessionId)));
      }
    }
    long t = now();
    engine.locked(w -> w.lastSessionAt = t);
    return status();
  }

  public Status status() {
    boolean active;
    synchronized (this) {
      active = sessionId != null && clock.nowMs() - lastBeat <= idleTimeout;
    }
    return new Status(now(), scriptedUntil(), backfill.running(), active);
  }

  private void start(long wall) {
    sessionId = Ulid.next(wall, RandomSource.seeded(System.nanoTime()).derive("session"));
    sessionStart = wall;
    long seed = engine.locked(w -> w.seed);
    pacer = new Pacer(lib.pacing(), RandomSource.seeded(seed).derive("pacer:" + sessionId));
    sessionRng = RandomSource.seeded(seed).derive("session:" + sessionId);
    typing.clear();
    dmSlots.clear();
    // a resident with a crush on R finds a reason to message him early in his session (SPEC §12 M8)
    crushAt = wall + 2 * 60_000L + (long) (sessionRng.nextDouble() * 18 * 60_000L);
    String id = sessionId;
    lane.run(() -> sql.commit(new UnitOfWork().add("INSERT INTO session_log (id, started_at, ended_at) VALUES (?, ?, ?)",
        id, wall, wall)));
    if (loop == null && !manual) loop = Thread.ofVirtual().name("live-play").start(this::run);
    wake.offer(true);
  }

  public void stop() {
    stopped = true;
    wake.offer(true);
  }

  // ---------------------------------------------------------------------------------- R's actions

  /** R posts: written and aired at once; responses follow at the Pacer's pace. Returns the post's id. */
  public String post(String text) {
    touch();
    var quick = Appraiser.reading(Appraiser.heuristic(text, false));
    long t = now(), wall = clock.nowMs();
    String id = actions.post(text, quick, t);
    // reflex (no LLM, milliseconds): close friends, fans and rivals react on relationship alone
    var reflex = actions.reflex(id, t);
    actions.save(reflex);
    var reacted = new LinkedHashSet<String>();
    reflex.reactions.forEach(r -> reacted.add(r.actorId()));
    var drama = engine.item(id);
    boolean dramatic = drama != null && (drama.controversy > 0.6 || drama.drama > 0.6);
    synchronized (this) {
      if (pacer != null) pacer.plan(id, wall, candidates(reflex, null), dramatic, false);
    }
    wake.offer(true);
    // considered (after the appraisal): who comments, and how; renders start at once
    Thread.ofVirtual().name("live-considered").start(() -> {
      try {
        var reading = appraiser.read(text, null);
        lane.run(() -> sql.commit(new UnitOfWork().add("UPDATE post SET appraisal_json = ? WHERE id = ?",
            reading.appraisalJson(), id)));
        var script = actions.considered(id, reading, reacted, t);
        plan(id, wall, script, null, dramatic, false);
      } catch (RuntimeException e) {
        log.warn("Live response planning failed for post {}", id, e);
      }
    });
    return id;
  }

  /** R comments on a post, or replies to a comment on it. */
  public Optional<String> comment(String postId, String parentId, String text) {
    touch();
    var post = engine.item(postId);
    if (post == null) return Optional.empty();
    var parent = parentId == null ? null : engine.item(parentId);
    var quick = Appraiser.reading(Appraiser.heuristic(text, true));
    long t = now(), wall = clock.nowMs();
    var id = actions.comment(postId, parentId, text, quick, t);
    if (id.isEmpty()) return id;
    String addressee = parent != null ? parent.authorId : post.authorId;
    String context = sql.queryOne("SELECT body FROM " + (parent != null ? "comment" : "post") + " WHERE id = ?",
        List.of(parent != null ? parentId : postId), r -> r.str("body")).orElse("");
    boolean dramatic = post.controversy > 0.6 || post.drama > 0.6;
    Thread.ofVirtual().name("live-considered").start(() -> {
      try {
        var reading = appraiser.read(text, context);
        lane.run(() -> sql.commit(new UnitOfWork().add("UPDATE comment SET appraisal_json = ? WHERE id = ?",
            reading.appraisalJson(), id.get())));
        var script = actions.considered(id.get(), reading, t);
        plan(id.get(), wall, script, addressee, dramatic, true);
      } catch (RuntimeException e) {
        log.warn("Live response planning failed for comment {}", id.get(), e);
      }
    });
    return id;
  }

  public boolean react(ReactionRow.Target type, String targetId, ReactionRow.Kind kind) {
    touch();
    return actions.react(type, targetId, kind, now());
  }

  public void unreact(ReactionRow.Target type, String targetId) {
    touch();
    actions.unreact(type, targetId);
  }

  // -------------------------------------------------------------------------------------- Messenger

  /** The thread between R and a resident, found or started. */
  public String openThread(String residentId) {
    touch();
    return actions.thread(residentId);
  }

  /**
   * R sends a DM (SPEC §5.11): saved and shown at once. The resident reads it (Seen) and starts typing
   * within seconds; the reply lands as soon as it's rendered plus a short typing delay, sometimes as a
   * burst. Someone who can't stand him may just leave it on Seen.
   */
  public Optional<String> dm(String threadId, String text) {
    touch();
    var quick = Appraiser.reading(Appraiser.heuristic(text, true));
    long t = now(), wall = clock.nowMs();
    var id = actions.dm(threadId, text, quick, t);
    if (id.isEmpty()) return id;
    Thread.ofVirtual().name("live-dm").start(() -> {
      try {
        var reading = appraiser.read(text, "a direct message");
        lane.run(() -> sql.commit(new UnitOfWork().add("UPDATE dm_message SET appraisal_json = ? WHERE id = ?",
            reading.appraisalJson(), id.get())));
        var reply = actions.dmReply(threadId, id.get(), coldShoulderRate, t);
        if (reply.leftOnSeen()) {
          emit("dm.seen", Map.of("threadId", threadId, "at", t));
          return;
        }
        actions.save(reply.script());
        queueDms(reply.script(), wall, true);
      } catch (RuntimeException e) {
        log.warn("Live DM reply failed for thread {}", threadId, e);
      }
    });
    return id;
  }

  /** R has read a thread: the resident's messages get their Seen. */
  public void readThread(String threadId) {
    touch();
    String user = engine.locked(w -> w.userId);
    actions.markRead(threadId, user, now());
  }

  /** Typing dots in a thread right now. */
  public synchronized List<Typing> typingIn(String threadId) {
    return dmSlots.stream().filter(x -> x.typing && x.threadId.equals(threadId))
        .map(x -> new Typing(x.key, threadId, x.actorId, x.name, x.typingAt)).toList();
  }

  /** Typing indicators on {@code postId} right now, for a post view loaded mid-indicator. */
  public synchronized List<Typing> typingOn(String postId) {
    return typing.values().stream().filter(x -> x.announced && x.postId.equals(postId)).map(Slot::view).toList();
  }

  public synchronized Monitor monitor() {
    long wall = clock.nowMs();
    return new Monitor(sessionId, sessionId == null ? null : sessionStart, sessionId == null ? null : lastBeat,
        typing.values().stream().filter(x -> x.announced).map(Slot::view).toList(),
        pacer == null ? null : pacer.snapshot(wall));
  }

  // ------------------------------------------------------------------------------------- planning

  private void plan(String trigger, long wall, Script script, String addressee, boolean dramatic, boolean reply) {
    actions.save(script);
    var items = candidates(script, addressee);
    synchronized (this) {
      if (pacer != null) pacer.plan(trigger, wall, items, dramatic, reply);
    }
    wake.offer(true);
    var posts = new LinkedHashSet<String>();
    script.comments.forEach(c -> posts.add(c.postId()));
    posts.forEach(this::render);
  }

  /** The planned responses as Pacer candidates, with their priority inputs. */
  private List<Pacer.Candidate> candidates(Script script, String addressee) {
    return engine.locked(w -> {
      var out = new ArrayList<Pacer.Candidate>();
      for (var r : script.reactions) {
        var target = w.items.get(r.targetId());
        String postId = target == null ? r.targetId() : target.type == Item.Type.POST ? target.id : target.postId;
        out.add(new Pacer.Candidate("REACTION:" + Script.reactionId(r), Pacer.Kind.REACTION, r.actorId(), postId,
            closeness(w, r.actorId()), 0.3, target == null ? 0 : target.drama, false));
      }
      for (var c : script.comments) {
        var post = w.items.get(c.postId());
        boolean toR = w.userId.equals(Brief.parse(c.briefJson()).toward());
        boolean isAddressee = addressee != null && addressee.equals(c.authorId());
        out.add(new Pacer.Candidate("COMMENT:" + c.id(), Pacer.Kind.COMMENT, c.authorId(), c.postId(),
            closeness(w, c.authorId()), isAddressee ? 1.0 : toR ? 0.7 : 0.4, post == null ? 0 : post.drama, isAddressee));
      }
      return out;
    });
  }

  private static double closeness(app.radiance.sim.model.SimWorld w, String actorId) {
    var e = w.edgeOrNull(actorId, w.userId);
    if (e == null) return 0;
    return Math.max(0, Math.min(1, 0.5 * (e.affinity + 100) / 200 + 0.5 * e.familiarity / 100));
  }

  /** Renders the live responses on {@code postId} now, on a virtual thread, one render per post at a time. */
  private void render(String postId) {
    Thread.ofVirtual().name("live-render").start(() -> {
      var lock = renderLocks.computeIfAbsent(postId, k -> new ReentrantLock());
      lock.lock();
      try {
        new Renderer(sql, lane, lib, gateway, engine.world(), networkName, softening, Priority.LIVE).renderThread(postId);
      } catch (RuntimeException e) {
        // the batch picks it up later; the Pacer's typing indicator just goes away
        log.warn("Live render failed for post {}: {}", postId, e.toString());
        long wall = clock.nowMs();
        var uow = new UnitOfWork();
        new JobRepository(sql).enqueue(uow, JobKinds.RENDER_THREAD, postId, JobKinds.BACKFILL, wall, wall, wall);
        lane.run(() -> sql.commit(uow));
      } finally {
        lock.unlock();
        wake.offer(true);
      }
    });
  }

  // ----------------------------------------------------------------------------------------- loop

  private void run() {
    while (!stopped) {
      long sleep;
      try {
        sleep = tick();
      } catch (RuntimeException e) {
        log.warn("Live loop turn failed", e);
        sleep = 1_000;
      }
      try {
        wake.poll(Math.max(20, sleep), TimeUnit.MILLISECONDS);
        wake.clear();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** One turn of the loop. Returns how long to sleep; between sessions, until the next one wakes it. */
  synchronized long tick() {
    long wall = clock.nowMs();
    if (sessionId == null) return 60_000;
    if (wall - lastBeat > idleTimeout) {
      end();
      return 60_000;
    }
    if (!lease.isHeld()) return 1_000;
    long t = now();

    // background activity keeps airing from the script
    if (wall - lastAir >= AIR_EVERY) {
      lastAir = wall;
      engine.tryAdvanceTo(t, 50);
    }

    // the Pacer's releases; a comment whose render is late shows a typing indicator instead
    var due = pacer.due(wall);
    actions.drop(pacer.dropped());
    var ready = new ArrayList<String>();
    for (var c : due) {
      if (c.kind() == Pacer.Kind.REACTION) ready.add(c.key());
      else typing.put(c.key(), new Slot(c.key(), c.postId(), c.actorId(), name(c.actorId()), wall));
    }
    if (!typing.isEmpty()) {
      var status = renderStatus(typing.keySet());
      for (var it = typing.values().iterator(); it.hasNext(); ) {
        var slot = it.next();
        String s = status.getOrDefault(slot.key, "DROPPED");
        if (s.equals("PLANNED") && wall - slot.since <= pacer.typingGiveUp()) {
          // its slot came before its render: R sees "Sarah is writing a comment…" until it lands
          if (!slot.announced && lib.pacing().typing().showWhenLate()) {
            slot.announced = true;
            emit("typing.started", Map.of("postId", slot.postId, "actorId", slot.actorId, "name", slot.name,
                "key", slot.key, "kind", "comment"));
          }
          continue;
        }
        // rendered: it lands now; failed or gave up: the indicator goes away, like a deleted draft
        if (s.equals("RENDERED")) ready.add(slot.key);
        if (slot.announced) emit("typing.stopped", Map.of("postId", slot.postId, "actorId", slot.actorId, "key", slot.key));
        it.remove();
      }
    }
    if (!ready.isEmpty()) actions.release(ready, t);
    dmTurn(t, wall);
    if (wall >= crushAt) {
      crushAt = Long.MAX_VALUE;
      crushCheckIn(t, wall);
    }

    publishAired(wall);

    for (var d : pacer.deliveries(wall)) {
      var names = d.actorIds().stream().map(this::name).toList();
      var data = new LinkedHashMap<String, Object>();
      data.put("kind", d.kind());
      data.put("refType", d.refType());
      data.put("refId", d.refId());
      data.put("actors", names);
      data.put("count", d.actorIds().size());
      data.put("ids", d.noteIds());
      emit("notification.created", data);
      pacer.seen(wall);
    }

    if (backfill.running() && wall - lastProgress >= 5_000) {
      lastProgress = wall;
      long posts = sql.queryOne("SELECT COUNT(*) AS n FROM post WHERE air_status = 'AIRED' AND render_status = 'RENDERED'",
          List.of(), r -> r.lng("n")).orElse(0L);
      emit("backfill.progress", Map.of("posts", posts));
    }

    if (pacer.hookDue(wall)) hook(t, wall);
    long next = Math.min(pacer.nextWake(wall), lastAir + AIR_EVERY);
    if (!typing.isEmpty() || !dmSlots.isEmpty()) next = Math.min(next, wall + 250);
    if (crushAt != Long.MAX_VALUE) next = Math.min(next, crushAt);
    return Math.max(20, Math.min(1_000, next - wall));
  }

  /** Turns what aired into stream events, and its notifications into the Pacer's queue. */
  private void publishAired(long wall) {
    WorldEngine.Report r;
    int newPosts = 0;
    String userId = null;
    while ((r = aired.poll()) != null) {
      if (userId == null) userId = engine.locked(w -> w.userId);
      var commentIds = new ArrayList<String>();
      for (String key : r.airedKeys()) {
        int i = key.indexOf(':');
        String type = key.substring(0, i), id = key.substring(i + 1);
        switch (type) {
          case "POST" -> {
            var author = sql.queryOne("SELECT author_id FROM post WHERE id = ? AND render_status = 'RENDERED'",
                List.of(id), x -> x.str("author_id")).orElse(null);
            if (author == null) break;
            emit("post.aired", Map.of("id", id, "authorId", author));
            if (!author.equals(userId) && visible(author, userId)) newPosts++;
          }
          case "COMMENT" -> commentIds.add(id);
          case "DM" -> sql.queryOne("SELECT thread_id, sender_id FROM dm_message WHERE id = ? AND render_status = 'RENDERED'",
                  List.of(id), x -> new String[] {x.str("thread_id"), x.str("sender_id")})
              .ifPresent(m -> emit("dm.message", Map.of("id", id, "threadId", m[0], "senderId", m[1])));
          case "REACTION" -> {
            var k = id.split(":", 3);
            emit("reaction.changed", Map.of("targetType", k[0], "targetId", k[1], "actorId", k[2]));
          }
          default -> {}
        }
      }
      for (String id : commentIds) {
        sql.queryOne("SELECT post_id, author_id FROM comment WHERE id = ? AND render_status = 'RENDERED'", List.of(id),
            x -> new String[] {x.str("post_id"), x.str("author_id")})
            .ifPresent(c -> emit("comment.aired", Map.of("id", id, "postId", c[0], "authorId", c[1])));
      }
      for (var n : r.notes()) {
        pacer.notify(new Pacer.Note(n.id(), n.kind(), n.actorId(), n.refType(), n.refId(), n.createdAt()));
      }
    }
    if (newPosts > 0) {
      emit("feed.new", Map.of("count", newPosts));
      pacer.seen(wall);
    }
  }

  private void hook(long t, long wall) {
    pacer.hooked(wall);
    var kinds = lib.pacing().hooks().kinds();
    // half the time, try a DM from someone with a reason first
    if (kinds.contains("dm_from_motivated_resident") && sessionRng.chance(0.5) && dmHook(t, wall, false)) return;
    if (kinds.contains("comment_on_older_post")) {
      var script = actions.hook(t);
      if (script.size() > 0) {
        String trigger = "hook:" + wall;
        actions.save(script);
        pacer.plan(trigger, wall, candidates(script, null), false, false);
        script.comments.stream().map(c -> c.postId()).distinct().forEach(this::render);
        return;
      }
    }
    if (kinds.contains("dm_from_motivated_resident") && dmHook(t, wall, false)) return;
    if (kinds.contains("pull_scripted_post_forward")) actions.pullForward(t, Durations.parse("60m").toMillis());
  }

  /**
   * Queues a planned burst of DMs: the first starts typing after the Pacer's 1–3 s (or at once, for a
   * resident who starts the conversation), each later one after the burst gap once the one before it
   * has landed. Renders start now.
   */
  private void queueDms(Script script, long wall, boolean reply) {
    if (script.dms.isEmpty()) return;
    synchronized (this) {
      if (pacer == null) return;
      DmSlot prev = null;
      for (var m : script.dms) {
        var slot = new DmSlot("DM:" + m.id(), m.id(), m.threadId(), m.senderId(), name(m.senderId()), reply);
        if (prev == null) slot.typingAt = wall + (reply ? pacer.dmTypingStart() : 0);
        else slot.after = prev;
        dmSlots.add(slot);
        prev = slot;
      }
    }
    wake.offer(true);
    script.dms.stream().map(m -> m.threadId()).distinct().forEach(this::renderDms);
  }

  /** One loop turn for DMs: typing dots, Seen, and messages landing when their typing is done. */
  private void dmTurn(long t, long wall) {
    if (dmSlots.isEmpty()) return;
    var landed = new ArrayList<DmSlot>();
    for (var it = dmSlots.iterator(); it.hasNext(); ) {
      var slot = it.next();
      if (slot.after != null && !slot.after.done) continue;
      if (slot.after != null && slot.typingAt == Long.MAX_VALUE) slot.typingAt = slot.after.landedAt + pacer.dmBurstGap();
      if (wall < slot.typingAt) continue;
      if (!slot.typing) {
        slot.typing = true;
        if (slot.reply && slot.after == null) {
          // they've read it: Seen shows as their typing starts
          actions.markRead(slot.threadId, slot.actorId, t);
          emit("dm.seen", Map.of("threadId", slot.threadId, "at", t));
        }
        emit("typing.started", Map.of("threadId", slot.threadId, "actorId", slot.actorId, "name", slot.name, "key", slot.key,
            "kind", "dm"));
      }
      var row = sql.queryOne("SELECT render_status, air_status, body FROM dm_message WHERE id = ?", List.of(slot.id),
          x -> new String[] {x.str("render_status"), x.str("air_status"), x.str("body")}).orElse(null);
      boolean failed = row == null || row[1].equals("DROPPED") || row[0].equals("DROPPED") || row[0].equals("FAILED");
      if (failed || (!row[0].equals("RENDERED") && wall - slot.typingAt > pacer.typingGiveUp())) {
        // the render failed or gave up: the dots just go away
        emit("typing.stopped", Map.of("threadId", slot.threadId, "actorId", slot.actorId, "key", slot.key));
        slot.done = true;
        slot.landedAt = wall;
        it.remove();
        continue;
      }
      if (!row[0].equals("RENDERED")) continue;
      if (slot.landAt == Long.MAX_VALUE) slot.landAt = wall + pacer.dmTypingDelay(row[2] == null ? 0 : row[2].length());
      if (wall < slot.landAt) continue;
      landed.add(slot);
      slot.done = true;
      slot.landedAt = wall;
      it.remove();
    }
    if (landed.isEmpty()) return;
    actions.release(landed.stream().map(x -> x.key).toList(), t);
    for (var slot : landed) {
      emit("typing.stopped", Map.of("threadId", slot.threadId, "actorId", slot.actorId, "key", slot.key));
    }
  }

  /** The crush who checks in early in R's session: once a session, if anyone has a crush on him. */
  private void crushCheckIn(long t, long wall) {
    if (!sessionRng.chance(Math.min(1, engine.knobs().dmInitiationRate()))) return;
    dmHook(t, wall, true);
  }

  /** A DM from someone with a reason to write; false if nobody has one right now. */
  private boolean dmHook(long t, long wall, boolean crushOnly) {
    var who = actions.motivatedToDm(t, crushOnly);
    if (who.isEmpty()) return false;
    String resident = who.getFirst();
    boolean crush = engine.locked(w -> {
      var e = w.edgeOrNull(resident, w.userId);
      return e != null && e.hasTag("crush");
    });
    String def = crush ? "dm_user_crush_checkin"
        : List.of("dm_user_gossip", "dm_user_advice", "dm_user_vent").get(sessionRng.nextInt(3));
    var script = actions.dmFrom(resident, def, t);
    if (script.dms.isEmpty()) return false;
    actions.save(script);
    queueDms(script, wall, false);
    pacer.seen(wall);
    return true;
  }

  /** Renders the live DMs in a thread now, one render per thread at a time. */
  private void renderDms(String threadId) {
    Thread.ofVirtual().name("live-render-dm").start(() -> {
      var lock = renderLocks.computeIfAbsent("dm:" + threadId, k -> new ReentrantLock());
      lock.lock();
      try {
        new Renderer(sql, lane, lib, gateway, engine.world(), networkName, softening, Priority.LIVE).renderDms(threadId);
      } catch (RuntimeException e) {
        log.warn("Live DM render failed for thread {}: {}", threadId, e.toString());
        long wallNow = clock.nowMs();
        var uow = new UnitOfWork();
        new JobRepository(sql).enqueue(uow, JobKinds.RENDER_DM, threadId, JobKinds.BACKFILL, wallNow, wallNow, wallNow);
        lane.run(() -> sql.commit(uow));
      } finally {
        lock.unlock();
        wake.offer(true);
      }
    });
  }

  /** A DM on its way to R: typing, then landing. */
  private static final class DmSlot {
    final String key, id, threadId, actorId, name;
    final boolean reply;
    DmSlot after;
    long typingAt = Long.MAX_VALUE, landAt = Long.MAX_VALUE, landedAt;
    boolean typing, done;

    DmSlot(String key, String id, String threadId, String actorId, String name, boolean reply) {
      this.key = key;
      this.id = id;
      this.threadId = threadId;
      this.actorId = actorId;
      this.name = name;
      this.reply = reply;
    }
  }

  private void end() {
    String id = sessionId;
    long at = lastBeat;
    lane.run(() -> sql.commit(new UnitOfWork().add("UPDATE session_log SET ended_at = ? WHERE id = ?", at, id)));
    for (var slot : typing.values()) {
      if (slot.announced) emit("typing.stopped", Map.of("postId", slot.postId, "actorId", slot.actorId, "key", slot.key));
    }
    for (var slot : dmSlots) {
      if (slot.typing) emit("typing.stopped", Map.of("threadId", slot.threadId, "actorId", slot.actorId, "key", slot.key));
    }
    sessionId = null;
    pacer = null;
    typing.clear();
    dmSlots.clear();
    crushAt = Long.MAX_VALUE;
  }

  // ---------------------------------------------------------------------------------------- reads

  private Map<String, String> renderStatus(Set<String> keys) {
    var out = new LinkedHashMap<String, String>();
    for (String key : keys) {
      sql.queryOne("SELECT render_status, air_status FROM comment WHERE id = ?", List.of(key.substring(8)),
          r -> r.str("air_status").equals("DROPPED") ? "DROPPED" : r.str("render_status"))
          .ifPresent(s -> out.put(key, s));
    }
    return out;
  }

  private boolean visible(String authorId, String userId) {
    return sql.queryOne("SELECT connected FROM relationship WHERE from_id = ? AND to_id = ?", List.of(userId, authorId),
        r -> r.lng("connected") != 0).orElse(false);
  }

  private String name(String actorId) {
    return engine.locked(w -> {
      var p = w.people.get(actorId);
      return p == null ? "Someone" : p.name;
    });
  }

  private boolean onboarded() {
    return engine.locked(w -> w.userId != null && !w.residents().isEmpty());
  }

  private long scriptedUntil() {
    return sql.queryOne("SELECT scripted_until FROM world", List.of(), r -> r.lng("scripted_until")).orElse(0L);
  }

  /** A released comment waiting on its render. */
  private static final class Slot {
    final String key, postId, actorId, name;
    final long since;
    boolean announced;

    Slot(String key, String postId, String actorId, String name, long since) {
      this.key = key;
      this.postId = postId;
      this.actorId = actorId;
      this.name = name;
      this.since = since;
    }

    Typing view() {
      return new Typing(key, postId, actorId, name, since);
    }
  }

  private void emit(String name, Map<String, Object> data) {
    var e = new LiveEvent(name, data);
    for (var l : listeners) {
      try {
        l.accept(e);
      } catch (RuntimeException ex) {
        log.debug("Stream listener failed", ex);
      }
    }
  }
}
