package app.radiance.sim;

import app.radiance.content.Vocab.Stance;
import app.radiance.graph.RelationshipRow;
import app.radiance.kernel.AirStatus;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.Json;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.RenderStatus;
import app.radiance.kernel.Ulid;
import app.radiance.messenger.DmRepository;
import app.radiance.messenger.DmRow;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.model.Edge;
import app.radiance.sim.model.Item;
import app.radiance.sim.model.SimWorld;
import app.radiance.social.CommentRow;
import app.radiance.social.PostRow;
import app.radiance.social.ReactionRow;
import app.radiance.social.SocialRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * R's actions in live play (SPEC §5.1): written and aired at once, so he sees them immediately, then
 * the responses planned in two phases. <em>Reflex</em> (no LLM, milliseconds): residents whose
 * relationship with R alone makes them react. <em>Considered</em> (after the appraisal): the engagement
 * model picks who comments or replies, and with what stance. Responses are LIVE scripted items timed
 * past {@link #HORIZON}; the Pacer {@link #release releases} each one by moving it up to its slot, and
 * whatever it never releases (R left, the app restarted) still airs, later, as background.
 */
public final class UserActions {

  /** Responses are scripted at least this far out; the Pacer's curves end well before it. */
  public static final long HORIZON = 12 * 60_000L;

  /** How R's post or comment reads, from the appraisal (SPEC §5.9). */
  public record Reading(Map<String, Double> features, List<String> topics, double controversy, String stance,
      String appraisalJson) {}

  private final Sql sql;
  private final WriterLane lane;
  private final WorldEngine engine;
  private final LiveResponder responder;

  public UserActions(Sql sql, WriterLane lane, WorldEngine engine) {
    this.sql = sql;
    this.lane = lane;
    this.engine = engine;
    this.responder = new LiveResponder(engine.lib(), engine.knobs());
  }

  // ------------------------------------------------------------------------------------- R's writes

  /** R's post, aired at once. Returns its id. */
  public String post(String text, Reading r, long now) {
    return engine.locked(w -> {
      long t = Math.max(now, w.time + 1);
      double edge = r.features().getOrDefault("edginess", 0.0);
      var brief = new Brief(null, null, "R's own post", null, null, null, r.topics(), Math.max(r.controversy(), edge * 0.6),
          edge * 0.4, Plan.extra("valence", r.features().getOrDefault("positivity", 0.5) - 0.4));
      // R's actions are inputs: the same session replays to the same ids, and so the same responses
      String id = Ulid.next(t, RandomSource.seeded(w.seed).derive("r-post:" + t + ":" + text));
      var row = new PostRow(id, w.userId, t, PostRow.Kind.STATUS, PostRow.Audience.FRIENDS, ContentOrigin.USER,
          AirStatus.SCRIPTED, text, RenderStatus.RENDERED, brief.json(), r.appraisalJson(), null, null, null, null, 3,
          Fidelity.FULL, "{}", 0);
      lane.run(() -> sql.commit(new SocialRepository(sql).savePosts(new UnitOfWork(), List.of(row))));
      engine.advanceTo(t);
      return id;
    });
  }

  /** R's comment on {@code postId}, or his reply to {@code parentId} on it, aired at once. */
  public Optional<String> comment(String postId, String parentId, String text, Reading r, long now) {
    return engine.locked(w -> {
      var post = item(w, postId);
      if (post == null || post.type != Item.Type.POST) return Optional.<String>empty();
      var parent = parentId == null ? null : item(w, parentId);
      long t = Math.max(now, w.time + 1);
      String toward = parent != null ? parent.authorId : post.authorId;
      if (toward.equals(w.userId)) toward = null;
      String stance = r.stance() == null ? Stance.SUPPORT.name() : r.stance();
      var brief = new Brief(post.defId, post.category, null, stance, toward, Airing.interactionFor(stance), post.topics,
          Math.max(post.controversy, r.controversy()), post.drama, Plan.extra("on", post.id));
      String id = Ulid.next(t, RandomSource.seeded(w.seed).derive("r-comment:" + t + ":" + postId + ":" + text));
      var row = new CommentRow(id, post.id, parent == null ? null : parent.id, w.userId, t, ContentOrigin.USER,
          AirStatus.SCRIPTED, text, null, RenderStatus.RENDERED, brief.json(), r.appraisalJson(), "{}");
      lane.run(() -> sql.commit(new SocialRepository(sql).saveComments(new UnitOfWork(), List.of(row))));
      engine.advanceTo(t);
      return Optional.of(id);
    });
  }

  /**
   * R reacts to a post or comment. A new reaction airs like any other (its effects land on the author);
   * changing it only changes the kind, and the counts.
   */
  public boolean react(ReactionRow.Target type, String targetId, ReactionRow.Kind kind, long now) {
    return engine.locked(w -> {
      var target = item(w, targetId);
      if (target == null) return false;
      var existing = sql.queryOne("SELECT kind FROM reaction WHERE target_type = ? AND target_id = ? AND actor_id = ?",
          List.of(type.name(), targetId, w.userId), x -> x.str("kind"));
      if (existing.isPresent()) {
        if (existing.get().equals(kind.name())) return true;
        target.reactionCounts.computeIfPresent(existing.get(), (k, v) -> v > 1 ? v - 1 : null);
        target.reactionCounts.merge(kind.name(), 1, Integer::sum);
        var uow = new UnitOfWork().add("UPDATE reaction SET kind = ? WHERE target_type = ? AND target_id = ? AND actor_id = ?",
            kind.name(), type.name(), targetId, w.userId);
        counts(uow, target);
        lane.run(() -> sql.commit(uow));
        return true;
      }
      long t = Math.max(now, w.time + 1);
      var row = new ReactionRow(type, targetId, w.userId, kind, t, AirStatus.SCRIPTED);
      lane.run(() -> sql.commit(new SocialRepository(sql).saveReactions(new UnitOfWork(), List.of(row))));
      engine.advanceTo(t);
      return true;
    });
  }

  /** R takes his reaction back. */
  public void unreact(ReactionRow.Target type, String targetId) {
    engine.locked(w -> {
      var existing = sql.queryOne("SELECT kind FROM reaction WHERE target_type = ? AND target_id = ? AND actor_id = ?",
          List.of(type.name(), targetId, w.userId), x -> x.str("kind"));
      if (existing.isEmpty()) return null;
      var uow = new UnitOfWork().add("DELETE FROM reaction WHERE target_type = ? AND target_id = ? AND actor_id = ?",
          type.name(), targetId, w.userId);
      var target = item(w, targetId);
      if (target != null) {
        target.reactors.remove(w.userId);
        target.reactionCounts.computeIfPresent(existing.get(), (k, v) -> v > 1 ? v - 1 : null);
        counts(uow, target);
      }
      lane.run(() -> sql.commit(uow));
      return null;
    });
  }

  // -------------------------------------------------------------------------------------- responses

  /**
   * Reflex reactions to R's post: close friends, partners and family, fans and crushes, rivals and
   * anyone who feels strongly about him react without reading closely.
   */
  public Script reflex(String postId, long now) {
    return engine.locked(w -> {
      var post = item(w, postId);
      var plan = plan(w, now);
      if (post == null) return plan.script;
      var planner = new Planner(engine.lib(), engine.knobs());
      String day = w.day(now);
      for (var b : w.residents()) {
        var e = w.edgeOrNull(b.id, w.userId);
        if (e == null || !e.connected) continue;
        boolean close = e.kind == RelationshipRow.Kind.CLOSE_FRIEND || e.kind == RelationshipRow.Kind.PARTNER
            || e.kind == RelationshipRow.Kind.FAMILY;
        boolean strong = e.hasTag("fan") || e.hasTag("crush") || e.hasTag("rival") || e.hasTag("protective")
            || Math.abs(e.affinity) >= 45;
        if (!close && !strong) continue;
        if (planner.attentionUsed(plan, b, day) >= b.routine.attentionBudget()) continue;
        var rng = RandomSource.forActor(w.seed, b.id, now).derive("reflex:" + postId);
        if (!rng.chance(0.85)) continue;
        var kind = e.hasTag("rival") || e.affinity <= -45 ? (rng.chance(0.6) ? ReactionRow.Kind.HAHA : ReactionRow.Kind.ANGRY)
            : Stances.reaction(null, b, post, e.affinity, rng);
        plan.reaction(now + HORIZON + LiveResponder.delay(rng, 90_000, 3_600_000L), ReactionRow.Target.POST, postId, b.id,
            kind);
        planner.spend(b, day);
      }
      return plan.script;
    });
  }

  /** The considered phase for R's post: impressions, then who comments and reacts. */
  public Script considered(String postId, Reading r, Set<String> reacted, long now) {
    return engine.locked(w -> {
      var post = item(w, postId);
      if (post == null) return new Script();
      String day = w.day(now);
      int postsToday = (int) w.items.values().stream()
          .filter(i -> i.type == Item.Type.POST && w.userId.equals(i.authorId) && w.day(i.time).equals(day)).count();
      return responder.onPost(w, post, r.features(), Math.max(1, postsToday), now, HORIZON, reacted);
    });
  }

  /** Responses to R's comment: the person he addressed may answer; a few others react. */
  public Script considered(String commentId, Reading r, long now) {
    return engine.locked(w -> {
      var comment = item(w, commentId);
      return comment == null ? new Script() : responder.onComment(w, comment, r.features(), now, HORIZON);
    });
  }

  /**
   * A hook against dead air (SPEC §5.1): a resident with a real motive — a crush, a fan, a close friend
   * or a rival — comments on one of R's recent posts they haven't commented on yet.
   */
  public Script hook(long now) {
    return engine.locked(w -> {
      var plan = plan(w, now);
      var rng = RandomSource.forSlice(w.seed, now).derive("hook");
      var posts = w.items.values().stream().filter(i -> i.type == Item.Type.POST && i.aired()
          && w.userId.equals(i.authorId) && i.time < now - 5 * 60_000L && i.time > now - 7 * 86_400_000L).toList();
      if (posts.isEmpty()) return plan.script;
      String day = w.day(now);
      var planner = new Planner(engine.lib(), engine.knobs());
      var motivated = new ArrayList<app.radiance.sim.model.Person>();
      for (var b : w.residents()) {
        var e = w.edgeOrNull(b.id, w.userId);
        if (e == null || !e.connected) continue;
        if (planner.attentionUsed(plan, b, day) >= b.routine.attentionBudget()) continue;
        if (e.hasTag("crush") || e.hasTag("fan") || e.hasTag("rival") || e.kind == RelationshipRow.Kind.CLOSE_FRIEND
            || Math.abs(e.affinity) >= 40) motivated.add(b);
      }
      if (motivated.isEmpty()) return plan.script;
      var b = motivated.get(rng.nextInt(motivated.size()));
      var fresh = posts.stream().filter(p -> !p.commenters.contains(b.id)).toList();
      if (fresh.isEmpty()) return plan.script;
      var post = fresh.get(rng.nextInt(fresh.size()));
      var stance = Stances.comment(w, b, w.person(w.userId), post, engine.lib(), engine.knobs(), day, rng);
      if (stance == Stance.REACT_ONLY) stance = Stance.SUPPORT;
      plan.comment("hook:" + b.id + ":" + post.id, now + HORIZON + LiveResponder.delay(rng, 10 * 60_000L, 3_600_000L),
          post.id, null, b.id, Planner.commentBrief(post, stance, w.userId, null));
      planner.spend(b, day);
      return plan.script;
    });
  }

  /**
   * The other hook: the next scripted, rendered post within {@code within} airs now instead. Returns its
   * id, if there was one.
   */
  public Optional<String> pullForward(long now, long within) {
    return engine.locked(w -> {
      long t = Math.max(now, w.time + 1);
      var id = sql.queryOne("SELECT id FROM post WHERE air_status = 'SCRIPTED' AND render_status = 'RENDERED'"
          + " AND created_at > ? AND created_at <= ? AND author_id <> ? ORDER BY created_at LIMIT 1",
          List.of(t, t + within, w.userId), x -> x.str("id"));
      if (id.isEmpty()) return id;
      lane.run(() -> sql.commit(new UnitOfWork().add("UPDATE post SET created_at = ? WHERE id = ?", t, id.get())));
      engine.advanceTo(t);
      return id;
    });
  }

  /** Saves a planned response script (its comments go to the live renderer next). */
  public void save(Script s) {
    if (s.size() == 0) return;
    var uow = new UnitOfWork();
    var social = new SocialRepository(sql);
    social.saveComments(uow, s.comments);
    social.saveReactions(uow, s.reactions);
    var dms = new DmRepository(sql);
    dms.ensureThreads(uow, s.threads);
    dms.saveMessages(uow, s.dms);
    lane.run(() -> sql.commit(uow));
  }

  /**
   * The Pacer released these responses ({@code COMMENT:id} or {@code REACTION:type:target:actor}):
   * they air now, through the one airing path.
   */
  public WorldEngine.Report release(List<String> keys, long now) {
    return engine.locked(w -> {
      long t = Math.max(now, w.time + 1);
      var uow = new UnitOfWork();
      for (String key : keys) {
        if (key.startsWith("DM:")) {
          uow.add("UPDATE dm_message SET created_at = ? WHERE id = ? AND air_status = 'SCRIPTED'", t, key.substring(3));
        } else if (key.startsWith("COMMENT:")) {
          uow.add("UPDATE comment SET created_at = ? WHERE id = ? AND air_status = 'SCRIPTED'", t, key.substring(8));
        } else if (key.startsWith("REACTION:")) {
          var k = key.substring(9).split(":", 3);
          uow.add("UPDATE reaction SET created_at = ? WHERE target_type = ? AND target_id = ? AND actor_id = ?"
              + " AND air_status = 'SCRIPTED'", t, k[0], k[1], k[2]);
        }
      }
      if (uow.size() > 0) lane.run(() -> sql.commit(uow));
      return engine.advanceTo(t);
    });
  }

  /** The Pacer dropped these reactions (held past the maximum hold): they never air. */
  public void drop(List<String> keys) {
    var uow = new UnitOfWork();
    for (String key : keys) {
      if (!key.startsWith("REACTION:")) continue;
      var k = key.substring(9).split(":", 3);
      uow.add("UPDATE reaction SET air_status = 'DROPPED' WHERE target_type = ? AND target_id = ? AND actor_id = ?"
          + " AND air_status = 'SCRIPTED'", k[0], k[1], k[2]);
    }
    if (uow.size() > 0) lane.run(() -> sql.commit(uow));
  }

  // ---------------------------------------------------------------------------------------- Messenger

  /** How a resident answers R's DM (SPEC §5.11): the planned messages, or left on Seen. */
  public record DmReply(Script script, boolean leftOnSeen, String tone) {}

  /** The thread between R and a resident, found or started (the same id a script would give it). */
  public String thread(String residentId) {
    return engine.locked(w -> {
      var existing = new DmRepository(sql).between(w.userId, residentId);
      if (existing.isPresent()) return existing.get().id();
      String a = w.userId.compareTo(residentId) < 0 ? w.userId : residentId;
      String b = a.equals(w.userId) ? residentId : w.userId;
      String id = Ulid.next(0, RandomSource.seeded(w.seed).derive("thread:" + Edge.pair(a, b)));
      var row = new DmRow.Thread(id, a, b, null, null);
      lane.run(() -> sql.commit(new DmRepository(sql).ensureThreads(new UnitOfWork(), List.of(row))));
      return id;
    });
  }

  /** R's message, aired at once. Empty if the thread isn't one of his. */
  public Optional<String> dm(String threadId, String text, Reading r, long now) {
    return engine.locked(w -> {
      var thread = new DmRepository(sql).thread(threadId).orElse(null);
      if (thread == null || !(w.userId.equals(thread.aId()) || w.userId.equals(thread.bId()))) return Optional.<String>empty();
      String other = w.userId.equals(thread.aId()) ? thread.bId() : thread.aId();
      long t = Math.max(now, w.time + 1);
      double flirt = r.features().getOrDefault("flirtiness", 0.0);
      double edge = r.features().getOrDefault("edginess", 0.0);
      String interaction = flirt > 0.5 ? "dm_flirt"
          : edge > 0.6 && r.features().getOrDefault("warmth", 0.5) < 0.3 ? "dm_curt" : "dm_warm";
      var brief = new Brief(null, "DM", null, r.stance(), other, interaction, r.topics(), r.controversy(), 0, Map.of());
      String id = Ulid.next(t, RandomSource.seeded(w.seed).derive("r-dm:" + t + ":" + threadId + ":" + text));
      var row = new DmRow.Message(id, threadId, w.userId, t, AirStatus.SCRIPTED, text, RenderStatus.RENDERED, brief.json(),
          r.appraisalJson(), null);
      lane.run(() -> sql.commit(new DmRepository(sql).saveMessages(new UnitOfWork(), List.of(row))));
      engine.advanceTo(t);
      return Optional.of(id);
    });
  }

  /**
   * How the resident answers R's latest message (SPEC §5.11). Cold shoulder, not silence: residents
   * who dislike R answer curtly, and only a hostile one (like-meter at -50 or below) may leave him on
   * Seen. Otherwise one to three texts, flirtier or warmer as the relationship and their faithfulness
   * allow, wrapping up when they've been at it a while. The engine decides all of it; the renderer
   * only writes the words.
   */
  public DmReply dmReply(String threadId, String messageId, double coldShoulderRate, long now) {
    return engine.locked(w -> {
      var thread = new DmRepository(sql).thread(threadId).orElse(null);
      var plan = plan(w, now);
      if (thread == null) return new DmReply(plan.script, false, "none");
      String other = w.userId.equals(thread.aId()) ? thread.bId() : thread.aId();
      var b = w.people.get(other);
      if (b == null) return new DmReply(plan.script, false, "none");
      var e = w.edge(other, w.userId);
      var rng = RandomSource.forActor(w.seed, other, now).derive("dm-reply:" + messageId);
      if (e.affinity <= -50 && rng.chance(Math.min(1, 0.6 * coldShoulderRate))) {
        markRead(threadId, other, now);
        var rule = engine.lib().relationships().interactions().get("dm_left_on_seen");
        if (rule != null) {
          Airing.move(w, w.userId, other, rule.target(), false, now,
              new Airing.Ctx(engine.lib(), engine.knobs(), null, false, Airing.Lookup.NONE));
        }
        return new DmReply(plan.script, true, "seen");
      }
      boolean curt = e.affinity < 0 && rng.chance(Math.min(1, (0.5 + Math.abs(e.affinity) / 100) * coldShoulderRate));
      var said = w.items.get(messageId);
      String day = w.day(now);
      Stance stance = said == null ? Stance.SUPPORT
          : Stances.comment(w, b, w.person(w.userId), said, engine.lib(), engine.knobs(), day, rng);
      if (stance == Stance.REACT_ONLY || stance == Stance.META) stance = Stance.SUPPORT;
      if (curt && (stance == Stance.FLIRT || stance == Stance.HYPE || stance == Stance.CONGRATS)) stance = Stance.SUPPORT;
      String interaction = curt ? "dm_curt" : stance == Stance.FLIRT ? "dm_flirt" : "dm_warm";
      int count = curt ? 1 : 1 + (rng.chance(0.4) ? 1 : 0) + (e.affinity > 30 && rng.chance(0.2) ? 1 : 0);
      long recent = sql.queryOne("SELECT COUNT(*) AS n FROM dm_message WHERE thread_id = ? AND sender_id = ?"
          + " AND created_at > ?", List.of(threadId, w.userId, now - 30 * 60_000L), x -> x.lng("n")).orElse(0L);
      boolean wrapUp = recent >= 8 || ("WORK".equals(b.location) && rng.chance(0.15));
      for (int i = 0; i < count; i++) {
        boolean last = i == count - 1;
        String text = i == 0
            ? (curt ? "answer R's message curtly: short, cool, minimal effort, one line" : "answer R's last message")
            : "a quick follow-up text in the same burst";
        if (last && wrapUp) text += "; then wrap the conversation up their way (gtg, ttyl, busy)";
        var extra = new java.util.LinkedHashMap<String, Object>();
        extra.put("tone", curt ? "curt" : "normal");
        extra.put("burst", i + 1);
        if (last && wrapUp) extra.put("endConversation", true);
        var brief = new Brief(null, "DM", text, stance.name(), w.userId, interaction, said == null ? List.of() : said.topics,
            0, 0, extra);
        plan.dm("dm-reply:" + messageId + ":" + i, now + HORIZON + i * 1_000L, other, w.userId, brief);
      }
      return new DmReply(plan.script, false, curt ? "curt" : "normal");
    });
  }

  /**
   * A resident starts a conversation with R (a DM_USER def, SPEC §5.11): as a hook while he's online,
   * or the crush who checks in early in his session.
   */
  public Script dmFrom(String residentId, String defId, long now) {
    return engine.locked(w -> {
      var plan = plan(w, now);
      var def = engine.lib().events().get(defId);
      if (def == null || !w.people.containsKey(residentId)) return plan.script;
      String text = def.output() != null && def.output().brief() != null ? def.output().brief() : "starts a conversation with R";
      String interaction = defId.contains("crush") ? "dm_flirt" : defId.contains("complaint") ? "dm_curt" : "dm_warm";
      var brief = new Brief(def.id(), "DM_USER", text, defId.contains("crush") ? "FLIRT" : null, w.userId, interaction,
          List.of(), 0, 0, Map.of());
      plan.dm("dm-from:" + defId + ":" + residentId, now + HORIZON, residentId, w.userId, brief);
      return plan.script;
    });
  }

  /**
   * Residents with a reason to message R now, best reason first: a crush, then fans, close friends and
   * the nosiest. None who messaged him in the last day.
   */
  public List<String> motivatedToDm(long now, boolean crushOnly) {
    return engine.locked(w -> {
      var out = new java.util.ArrayList<Map.Entry<String, Double>>();
      for (var b : w.residents()) {
        var e = w.edgeOrNull(b.id, w.userId);
        if (e == null) continue;
        double why = e.hasTag("crush") ? 3 : crushOnly ? 0 : e.hasTag("fan") ? 2
            : e.kind == RelationshipRow.Kind.CLOSE_FRIEND ? 1.5 : b.trait("nosiness") > 70 && e.affinity > 20 ? 1 : 0;
        if (why == 0) continue;
        long lately = sql.queryOne("SELECT COUNT(*) AS n FROM dm_message m JOIN dm_thread t ON t.id = m.thread_id"
            + " WHERE m.sender_id = ? AND m.created_at > ? AND (t.a_id = ? OR t.b_id = ?)",
            List.of(b.id, now - 86_400_000L, w.userId, w.userId), x -> x.lng("n")).orElse(0L);
        if (lately == 0) out.add(Map.entry(b.id, why + e.affinity / 1000));
      }
      out.sort(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
      return out.stream().map(Map.Entry::getKey).toList();
    });
  }

  /** {@code reader} has read the thread: every aired message to them gets a Seen. */
  public void markRead(String threadId, String reader, long t) {
    lane.run(() -> sql.commit(new UnitOfWork().add("UPDATE dm_message SET read_at = ? WHERE thread_id = ? AND sender_id <> ?"
        + " AND air_status = 'AIRED' AND read_at IS NULL", t, threadId, reader)));
  }

  // ------------------------------------------------------------------------------------------ helpers

  private Plan plan(SimWorld w, long t) {
    return new Plan(w, engine.lib(), engine.knobs(), new Slice(t, t + 3_600_000L, Fidelity.FULL),
        new Plan.Inputs(List.of(), null, Set.of(), Map.of(), ContentOrigin.LIVE), new HashMap<>());
  }

  private Item item(SimWorld w, String id) {
    return id == null ? null : engine.item(id);
  }

  private static void counts(UnitOfWork uow, Item target) {
    String table = target.type == Item.Type.POST ? "post" : "comment";
    uow.add("UPDATE " + table + " SET reaction_counts_json = ? WHERE id = ?", Json.write(target.reactionCounts), target.id);
  }
}
