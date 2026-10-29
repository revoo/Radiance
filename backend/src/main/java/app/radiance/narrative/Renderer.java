package app.radiance.narrative;

import app.radiance.content.ContentLibrary;
import app.radiance.kernel.Json;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Ulid;
import app.radiance.kernel.Visibility;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.LlmRequest;
import app.radiance.llm.Outcome;
import app.radiance.llm.Part;
import app.radiance.llm.Priority;
import app.radiance.llm.Purpose;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.model.SimWorld;
import app.radiance.social.ReactionRow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The script render path (SPEC §5.10): {@code RENDER_THREAD(postId)} writes a post and its scripted
 * comments in one call, {@code RENDER_DM(threadId)} the scripted DMs in a thread. Every item is
 * validated; a failing item is re-rendered once with the problem spelled out, then falls back to a
 * reaction (comments) or is dropped (posts, DMs). A refusal is never softened around: the resident just
 * reacts. Minor facts the prose introduces are recorded PENDING until their item airs.
 */
public final class Renderer {

  public static final String RENDER_THREAD = "RENDER_THREAD";
  public static final String RENDER_DM = "RENDER_DM";

  /** What one job did, for the batch report and the AC tests. */
  public record Result(int rendered, int rerendered, int fellBack, int dropped, List<String> problems) {}

  private static final Logger log = LoggerFactory.getLogger(Renderer.class);

  private final Sql sql;
  private final WriterLane lane;
  private final ContentLibrary lib;
  private final LlmGateway gateway;
  private final SimWorld world;
  private final ContextPacketBuilder packets;
  private final ProseChecks checks;
  private final Priority priority;

  public Renderer(Sql sql, WriterLane lane, ContentLibrary lib, LlmGateway gateway, SimWorld world, String networkName,
      boolean softeningCheck, Priority priority) {
    this.sql = sql;
    this.lane = lane;
    this.lib = lib;
    this.gateway = gateway;
    this.world = world;
    this.packets = new ContextPacketBuilder(sql, lib, networkName, world);
    this.checks = new ProseChecks(lib, gateway, world, softeningCheck);
    this.priority = priority;
  }

  public ContextPacketBuilder packets() {
    return packets;
  }

  public Result renderThread(String postId) {
    var packet = packets.thread(postId, gateway.budgetFor(Purpose.RENDER_THREAD));
    // fidelity (SPEC §5.1): REDUCED renders at most 3 comments per post, SUMMARY none
    String fidelity = sql.queryOne("SELECT fidelity FROM post WHERE id = ?", List.of(postId), r -> r.str("fidelity"))
        .orElse("FULL");
    int cap = fidelity.equals("SUMMARY") ? 0 : fidelity.equals("REDUCED") ? 3 : Integer.MAX_VALUE;
    return render(Purpose.RENDER_THREAD, packet, cap);
  }

  public Result renderDms(String threadId) {
    var packet = packets.dms(threadId, gateway.budgetFor(Purpose.RENDER_DM));
    return render(Purpose.RENDER_DM, packet, Integer.MAX_VALUE);
  }

  private Result render(Purpose purpose, ContextPacketBuilder.Packet packet, int commentCap) {
    var targets = new LinkedHashMap<>(packet.targets());
    if (targets.isEmpty()) return new Result(0, 0, 0, 0, List.of());
    var uow = new UnitOfWork();
    var problems = new ArrayList<String>();
    int fellBack = 0, dropped = 0;

    // the degradation ladder (SPEC §5.15): fewer comments, then reactions only, then nothing rendered
    int rung = gateway.degradationLevel();
    if (rung >= 5) return new Result(0, 0, 0, 0, List.of("degraded: structure only"));
    int cap = Math.min(commentCap, rung >= 4 ? 0 : rung >= 3 ? 2 : Integer.MAX_VALUE);
    if (cap < Integer.MAX_VALUE) {
      int kept = 0;
      for (var t : List.copyOf(targets.values())) {
        if (!t.type().equals("COMMENT") && !t.type().equals("REPLY")) continue;
        if (++kept > cap) {
          fallBack(uow, t);
          targets.remove(t.id());
          fellBack++;
        }
      }
    }

    var first = call(purpose, packet.prefix(), packet.content(), targets.size());
    if (first.outcome() == Outcome.REFUSED) {
      // every model on the route refused: the residents just react; the brief is never softened
      for (var t : targets.values()) {
        if (fallBack(uow, t)) fellBack++;
        else dropped++;
      }
      problems.add("refused by every model");
      commit(uow);
      return new Result(0, 0, fellBack, dropped, problems);
    }
    if (!first.ok()) throw new IllegalStateException("render call failed: " + first.outcome());
    var items = byId(first.value());
    var good = new LinkedHashMap<String, RenderBatch.Item>();
    var redo = new LinkedHashMap<String, List<ProseChecks.Problem>>();
    validate(targets, items, packet, good, redo);

    int rerendered = 0;
    if (!redo.isEmpty()) {
      rerendered = redo.size();
      var fix = new StringBuilder("\n\n<fix>\nRewrite only these briefs; the first attempt had problems:\n");
      redo.forEach((id, ps) -> fix.append("- ").append(id).append(": ")
          .append(String.join("; ", ps.stream().map(ProseChecks.Problem::detail).toList())).append('\n'));
      fix.append("</fix>");
      var second = call(purpose, packet.prefix(), packet.content() + fix, redo.size());
      var again = second.ok() ? byId(second.value()) : Map.<String, RenderBatch.Item>of();
      var retry = new LinkedHashMap<String, ContextPacketBuilder.Target>();
      redo.keySet().forEach(id -> retry.put(id, targets.get(id)));
      var stillBad = new LinkedHashMap<String, List<ProseChecks.Problem>>();
      validate(retry, again, packet, good, stillBad);
      for (var e : stillBad.entrySet()) {
        problems.add(e.getKey() + ": " + e.getValue().stream().map(ProseChecks.Problem::kind).toList());
        if (fallBack(uow, targets.get(e.getKey()))) fellBack++;
        else dropped++;
      }
    }
    for (var e : good.entrySet()) write(uow, targets.get(e.getKey()), e.getValue());
    commit(uow);
    if (!problems.isEmpty()) log.debug("Render fell back on {}", problems);
    return new Result(good.size(), rerendered, fellBack, dropped, problems);
  }

  private void validate(Map<String, ContextPacketBuilder.Target> targets, Map<String, RenderBatch.Item> items,
      ContextPacketBuilder.Packet packet, Map<String, RenderBatch.Item> good, Map<String, List<ProseChecks.Problem>> bad) {
    var involved = new LinkedHashSet<String>();
    targets.values().forEach(t -> involved.add(t.authorId()));
    for (var t : targets.values()) {
      var item = items.get(t.id());
      if (item == null) {
        bad.put(t.id(), List.of(new ProseChecks.Problem("missing", "this brief got no item; write it")));
        continue;
      }
      var author = world.people.get(t.authorId());
      String band = packets.lengthBand(t.authorId());
      long when = packets.timeOf(t);
      var ps = checks.check(t, item, author, band, involved, when);
      if (ps.isEmpty()) good.put(t.id(), item);
      else bad.put(t.id(), ps);
    }
  }

  private app.radiance.llm.LlmResult<RenderBatch> call(Purpose purpose, List<app.radiance.llm.Block> prefix, String content,
      int briefs) {
    int max = Math.min(8_000, 200 + 160 * briefs);
    var req = new LlmRequest<>(purpose, prefix, List.of(new Part.Text(content)), RenderBatch.class, max,
        Duration.ofSeconds(90), priority);
    var r = gateway.call(req);
    if (r.outcome() == Outcome.TRUNCATED) r = gateway.call(req.withMaxOutputTokens(max * 2));
    return r;
  }

  private static Map<String, RenderBatch.Item> byId(RenderBatch b) {
    var m = new HashMap<String, RenderBatch.Item>();
    if (b == null || b.items() == null) return m;
    for (var i : b.items()) if (i != null && i.briefId() != null) m.putIfAbsent(i.briefId(), i);
    return m;
  }

  private void write(UnitOfWork uow, ContextPacketBuilder.Target t, RenderBatch.Item item) {
    String text = item.text().strip();
    String gist = item.gist() == null ? null : item.gist().strip();
    switch (t.type()) {
      case "POST" -> {
        uow.add("UPDATE post SET body = ?, render_status = 'RENDERED' WHERE id = ? AND body IS NULL", text, t.id());
        if (t.recap() && t.eventId() != null && item.recapLine() != null && !item.recapLine().isBlank()) {
          uow.add("UPDATE event SET recap_line = ? WHERE id = ?", item.recapLine().strip(), t.eventId());
        }
      }
      case "COMMENT", "REPLY" -> {
        uow.add("UPDATE comment SET body = ?, gist = ?, render_status = 'RENDERED' WHERE id = ? AND body IS NULL", text,
            gist, t.id());
        uow.add("UPDATE post SET comment_count = (SELECT COUNT(*) FROM comment c WHERE c.post_id = post.id"
            + " AND c.air_status = 'AIRED' AND c.render_status = 'RENDERED') WHERE id = (SELECT post_id FROM comment WHERE id = ?)",
            t.id());
      }
      case "DM" -> uow.add("UPDATE dm_message SET body = ?, render_status = 'RENDERED' WHERE id = ? AND body IS NULL",
          text, t.id());
      default -> { }
    }
    facts(uow, t, item);
  }

  /** Minor facts become canon when their item airs (SPEC P2, §5.10): PENDING until then. */
  private void facts(UnitOfWork uow, ContextPacketBuilder.Target t, RenderBatch.Item item) {
    if (item.introducedFacts() == null) return;
    String status = aired(t) ? "ACTIVE" : "PENDING";
    var byHandle = world.byHandle();
    long at = packets.timeOf(t);
    int i = 0;
    for (var f : item.introducedFacts()) {
      if (f == null || f.major() || f.statement() == null) continue;
      String h = f.subject() == null ? "" : f.subject().replace("@", "").strip();
      var subject = byHandle.get(h);
      if (subject == null) continue;
      String id = Ulid.next(at, RandomSource.seeded(world.seed).derive("fact:" + t.id() + ":" + i++));
      uow.add("INSERT INTO fact (id, subject_id, statement, weight, source, status, visibility, valid_from, origin_ref)"
          + " VALUES (?, ?, ?, 'MINOR', 'PROSE', ?, ?, ?, ?) ON CONFLICT DO NOTHING", id, subject.id, f.statement().strip(),
          status, Visibility.PUBLIC.name(), at, t.id());
    }
  }

  private boolean aired(ContextPacketBuilder.Target t) {
    String table = switch (t.type()) {
      case "POST" -> "post";
      case "DM" -> "dm_message";
      default -> "comment";
    };
    return sql.queryOne("SELECT air_status AS s FROM " + table + " WHERE id = ?", List.of(t.id()), r -> r.str("s"))
        .map("AIRED"::equals).orElse(false);
  }

  /**
   * A comment that can't be written becomes its author's reaction to the post; a post or DM that can't
   * be written is dropped (airing then drops what depends on it). Returns true for a reaction.
   */
  private boolean fallBack(UnitOfWork uow, ContextPacketBuilder.Target t) {
    switch (t.type()) {
      case "COMMENT", "REPLY" -> {
        var row = sql.queryOne("SELECT post_id, created_at, air_status FROM comment WHERE id = ?", List.of(t.id()),
            r -> new String[] {r.str("post_id"), String.valueOf(r.lng("created_at")), r.str("air_status")}).orElse(null);
        if (row == null) return false;
        var kind = reactionFor(t.stance());
        uow.add("INSERT INTO reaction (target_type, target_id, actor_id, kind, created_at, air_status)"
            + " VALUES ('POST', ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING", row[0], t.authorId(), kind.name(),
            Long.parseLong(row[1]), row[2]);
        uow.add("UPDATE comment SET render_status = 'DROPPED', air_status = CASE WHEN air_status = 'SCRIPTED'"
            + " THEN 'DROPPED' ELSE air_status END WHERE id = ?", t.id());
        return true;
      }
      case "POST" -> uow.add("UPDATE post SET render_status = 'DROPPED', air_status = CASE WHEN air_status = 'SCRIPTED'"
          + " THEN 'DROPPED' ELSE air_status END WHERE id = ?", t.id());
      default -> uow.add("UPDATE dm_message SET render_status = 'DROPPED', air_status = CASE WHEN air_status = 'SCRIPTED'"
          + " THEN 'DROPPED' ELSE air_status END WHERE id = ?", t.id());
    }
    return false;
  }

  static ReactionRow.Kind reactionFor(String stance) {
    if (stance == null) return ReactionRow.Kind.LIKE;
    return switch (stance) {
      case "JOKE", "MOCK", "TROLL" -> ReactionRow.Kind.HAHA;
      case "DISAGREE", "HOSTILE" -> ReactionRow.Kind.ANGRY;
      case "CONDOLENCE" -> ReactionRow.Kind.CARE;
      case "CONGRATS", "HYPE", "FLIRT" -> ReactionRow.Kind.LOVE;
      default -> ReactionRow.Kind.LIKE;
    };
  }

  private void commit(UnitOfWork uow) {
    if (!uow.isEmpty()) lane.run(() -> sql.commit(uow));
  }

  static String json(Object o) {
    return Json.write(o);
  }
}
