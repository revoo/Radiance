package app.radiance.narrative;

import app.radiance.content.ContentLibrary;
import app.radiance.jobs.JobKinds;
import app.radiance.jobs.JobRepository;
import app.radiance.kernel.Json;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Ulid;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.LlmRequest;
import app.radiance.llm.Part;
import app.radiance.llm.Priority;
import app.radiance.llm.Purpose;
import app.radiance.memory.MemoryRepository;
import app.radiance.memory.MemoryRow;
import app.radiance.messenger.DmRepository;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.Impressions;
import app.radiance.sim.model.Edge;
import app.radiance.sim.model.SimWorld;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;

/**
 * The batch's maintenance step (SPEC §5.1 step 7, §5.7): REFLECT compacts an actor's old memories into
 * period summaries and a new "lately"; SUMMARY refreshes how one person sees another when the edge has
 * changed materially — 15 points of affinity since the last summary, or new impression tags toward R.
 * The state a summary was written at is kept with the edge ({@code ~sumaff}, {@code ~sumtags}).
 */
public final class Maintenance {

  static final long DAY = 86_400_000L;
  static final int COMPACT_OVER = 40;
  static final long COMPACT_AGE = 7 * DAY;
  static final double MATERIAL = 15;
  /** DM threads longer than this get their older messages summarized (SPEC §5.11). */
  static final int DM_SUMMARIZE_OVER = 60;
  /** Packets show this many recent messages; the summary covers the rest. */
  static final int DM_RECENT = 30;

  private final Sql sql;
  private final WriterLane lane;
  private final ContentLibrary lib;
  private final LlmGateway gateway;
  private final SimWorld world;
  private final String networkName;

  public Maintenance(Sql sql, WriterLane lane, ContentLibrary lib, LlmGateway gateway, SimWorld world, String networkName) {
    this.sql = sql;
    this.lane = lane;
    this.lib = lib;
    this.gateway = gateway;
    this.world = world;
    this.networkName = networkName;
  }

  /** Enqueues every REFLECT and SUMMARY that's due; returns how many. Paused under budget pressure. */
  public int enqueue(long wallNow) {
    if (gateway.degradationLevel() >= 1) return 0; // the ladder's first rung (SPEC §5.15)
    var uow = new UnitOfWork();
    var jobs = new JobRepository(sql);
    int n = 0;
    long cutoff = world.time - COMPACT_AGE;
    var counts = sql.query("SELECT owner_id AS o, COUNT(*) AS n FROM memory WHERE compacted = 0 AND world_time < ?"
        + " AND kind = 'EPISODE' GROUP BY owner_id", List.of(cutoff), r -> new Object[] {r.str("o"), r.lng("n")});
    for (var c : counts) {
      if ((long) c[1] > COMPACT_OVER) {
        jobs.enqueue(uow, JobKinds.REFLECT, (String) c[0], JobKinds.BACKGROUND, wallNow, wallNow);
        n++;
      }
    }
    for (var e : world.edges.values().stream().sorted(java.util.Comparator.comparing(x -> x.from + "|" + x.to)).toList()) {
      if (summaryDue(e)) {
        jobs.enqueue(uow, JobKinds.SUMMARY, e.from + "|" + e.to, JobKinds.BACKGROUND, wallNow, wallNow);
        n++;
      }
    }
    for (var t : sql.query("SELECT t.id AS id, t.summarized_count AS done, COUNT(m.id) AS n FROM dm_thread t"
        + " JOIN dm_message m ON m.thread_id = t.id AND m.body IS NOT NULL GROUP BY t.id, t.summarized_count", List.of(),
        r -> new Object[] {r.str("id"), r.lng("done"), r.lng("n")})) {
      if (dmSummaryDue((long) t[2], (long) t[1])) {
        jobs.enqueue(uow, JobKinds.DM_SUMMARY, (String) t[0], JobKinds.BACKGROUND, wallNow, wallNow);
        n++;
      }
    }
    if (!uow.isEmpty()) lane.run(() -> sql.commit(uow));
    return n;
  }

  /**
   * A thread of {@code written} messages needs a (new) summary once it's longer than 60 and at least 30
   * messages outside the packet's recent window aren't in the summary yet.
   */
  public static boolean dmSummaryDue(long written, long summarized) {
    return written > DM_SUMMARIZE_OVER && written - DM_RECENT - summarized >= DM_RECENT;
  }

  /** DM_SUMMARY: the earlier summary plus the messages that scrolled out of the recent window, in a few sentences. */
  public void summarizeThread(String threadId) {
    var thread = new DmRepository(sql).thread(threadId).orElse(null);
    if (thread == null) return;
    var written = new DmRepository(sql).messages(threadId).stream().filter(m -> m.body() != null).toList();
    long done = sql.queryOne("SELECT summarized_count FROM dm_thread WHERE id = ?", List.of(threadId),
        r -> r.lng("summarized_count")).orElse(0L);
    if (!dmSummaryDue(written.size(), done)) return;
    int upTo = written.size() - DM_RECENT;
    var text = new StringBuilder();
    for (var m : written.subList((int) done, upTo)) {
      var who = world.people.get(m.senderId());
      text.append(who == null ? "?" : who.name).append(": ").append(m.body()).append('\n');
    }
    var a = world.people.get(thread.aId());
    var b = world.people.get(thread.bId());
    String prompt = lib.prompt("dm-summary").replace("{{network_name}}", networkName)
        .replace("{{a}}", a == null ? "?" : a.name).replace("{{b}}", b == null ? "?" : b.name)
        .replace("{{earlier}}", thread.summary() == null ? "(none yet)" : thread.summary())
        .replace("{{messages}}", text.toString().strip());
    var r = gateway.call(new LlmRequest<>(Purpose.SUMMARY, List.of(), List.of(new Part.Text(prompt)), RelationSummary.class,
        400, Duration.ofSeconds(60), Priority.BACKGROUND));
    if (!r.ok() || r.value().summary() == null || r.value().summary().isBlank()) {
      throw new IllegalStateException("thread summary failed: " + r.outcome());
    }
    String summary = words(r.value().summary(), 160);
    var uow = new DmRepository(sql).summarize(new UnitOfWork(), threadId, summary);
    uow.add("UPDATE dm_thread SET summarized_count = ? WHERE id = ?", upTo, threadId);
    lane.run(() -> sql.commit(uow));
  }

  boolean summaryDue(Edge e) {
    var from = world.people.get(e.from);
    if (from == null || from.user) return false;
    if (e.to.equals(world.userId)) {
      var tags = impressionTags(e);
      return !tags.isEmpty() && e.pending.getOrDefault("~sumtags", 0L) != (long) tags.hashCode();
    }
    if (e.familiarity < 30) return false;
    Long at = e.pending.get("~sumaff");
    double last = at == null ? Double.NaN : at / 1000.0;
    return Double.isNaN(last) ? e.summary == null : Math.abs(e.affinity - last) >= MATERIAL;
  }

  static TreeSet<String> impressionTags(Edge e) {
    var out = new TreeSet<String>();
    for (String t : e.tags) if (Impressions.TAGS.contains(t)) out.add(t);
    return out;
  }

  /** REFLECT: 1–3 period summaries and a new "lately" (≤ 150 words); the originals are kept, compacted. */
  public void reflect(String actorId) {
    var person = world.people.get(actorId);
    if (person == null) return;
    long cutoff = world.time - COMPACT_AGE;
    var old = new MemoryRepository(sql).olderEpisodes(actorId, cutoff);
    if (old.size() <= COMPACT_OVER) return;
    String lately = sql.queryOne("SELECT life_summary FROM persona WHERE actor_id = ?", List.of(actorId),
        r -> r.isNull("life_summary") ? "" : r.str("life_summary")).orElse("");
    var text = new StringBuilder();
    for (var m : old) text.append("- ").append(world.day(m.worldTime())).append(": ").append(m.text()).append('\n');
    String prompt = lib.prompt("compaction").replace("{{name}}", person.name)
        .replace("{{lately}}", lately.isBlank() ? "(none yet)" : lately).replace("{{memories}}", text.toString().strip());
    var r = gateway.call(new LlmRequest<>(Purpose.COMPACTION, List.of(), List.of(new Part.Text(prompt)), Compacted.class,
        900, Duration.ofSeconds(90), Priority.BACKGROUND));
    if (!r.ok()) throw new IllegalStateException("compaction failed: " + r.outcome());
    var out = r.value();
    var summaries = out.periodSummaries() == null ? List.<String>of() : out.periodSummaries().stream()
        .filter(s -> s != null && !s.isBlank()).limit(3).toList();
    if (summaries.isEmpty()) return;
    var participants = new LinkedHashSet<String>();
    for (var m : old) Json.tree(m.participantsJson()).forEach(p -> participants.add(p.asString()));
    long at = old.getLast().worldTime();
    var uow = new UnitOfWork();
    var rows = new ArrayList<MemoryRow>();
    int i = 0;
    for (String s : summaries) {
      rows.add(new MemoryRow(Ulid.next(at, RandomSource.seeded(world.seed).derive("period:" + actorId + ":" + at + ":" + i++)),
          actorId, at, MemoryRow.Kind.PERIOD_SUMMARY, s.strip(), 6, Json.write(List.copyOf(participants)),
          Json.write(List.of("period")), null, null, false));
    }
    new MemoryRepository(sql).save(uow, rows);
    for (var m : old) uow.add("UPDATE memory SET compacted = 1 WHERE id = ?", m.id());
    uow.add("UPDATE persona SET life_summary = ? WHERE actor_id = ?", words(out.lifeSummary(), 150), actorId);
    lane.run(() -> sql.commit(uow));
  }

  /** SUMMARY: how {@code from} sees {@code to}, in 1–3 sentences, from the edge and recent memories. */
  public void summarize(String pair) {
    int i = pair.indexOf('|');
    String fromId = pair.substring(0, i), toId = pair.substring(i + 1);
    var e = world.edgeOrNull(fromId, toId);
    var from = world.people.get(fromId);
    var to = world.people.get(toId);
    if (e == null || from == null || to == null) return;
    var rel = new StringBuilder();
    rel.append(e.kind.name().toLowerCase().replace('_', ' ')).append("; affinity ").append(Math.round(e.affinity))
        .append(", trust ").append(Math.round(e.trust)).append(", respect ").append(Math.round(e.respect))
        .append(", familiarity ").append(Math.round(e.familiarity));
    var tags = e.tags.stream().toList();
    if (!tags.isEmpty()) rel.append("; tags: ").append(String.join(", ", tags));
    if (e.summary != null) rel.append("\nHow they know each other: ").append(e.summary);
    var memories = sql.query("SELECT text FROM memory WHERE owner_id = ? AND participants_json LIKE ? AND compacted = 0"
        + " ORDER BY world_time DESC LIMIT 8", List.of(fromId, "%" + toId + "%"), r -> r.str("text"));
    String prompt = lib.prompt("summary").replace("{{network_name}}", networkName).replace("{{from}}", from.name)
        .replace("{{to}}", to.name).replace("{{relationship}}", rel.toString())
        .replace("{{lately}}", memories.isEmpty() ? "(nothing much)" : "- " + String.join("\n- ", memories));
    var r = gateway.call(new LlmRequest<>(Purpose.SUMMARY, List.of(), List.of(new Part.Text(prompt)), RelationSummary.class,
        200, Duration.ofSeconds(60), Priority.BACKGROUND));
    if (!r.ok() || r.value().summary() == null || r.value().summary().isBlank()) {
      throw new IllegalStateException("summary failed: " + r.outcome());
    }
    String summary = words(r.value().summary(), 70);
    synchronized (world) {
      e.summary = summary;
      e.pending.put("~sumaff", Math.round(e.affinity * 1000));
      if (toId.equals(world.userId)) e.pending.put("~sumtags", (long) impressionTags(e).hashCode());
      e.dirty = true;
    }
    lane.run(() -> sql.commit(new UnitOfWork().add("UPDATE relationship SET summary = ?, pending_tags_json = ?"
        + " WHERE from_id = ? AND to_id = ?", summary, Json.write(e.pending), fromId, toId)));
  }

  static String words(String s, int max) {
    if (s == null) return null;
    var w = s.strip().split("\\s+");
    return w.length <= max ? s.strip() : String.join(" ", java.util.Arrays.copyOf(w, max));
  }
}
