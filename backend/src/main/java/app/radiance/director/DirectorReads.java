package app.radiance.director;

import app.radiance.content.ContentLibrary;
import app.radiance.kernel.Json;
import app.radiance.llm.LlmConsole;
import app.radiance.memory.MemoryRepository;
import app.radiance.memory.MemoryRetrieval;
import app.radiance.persistence.Sql;
import app.radiance.sim.Incidents;
import app.radiance.sim.WorldEngine;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** The console's reads: everything R isn't meant to see while he's playing (SPEC §5.16). */
final class DirectorReads {

  record Who(String id, String handle, String name, boolean you, String avatarUrl) {}

  record ArcView(String id, String defId, String description, String status, String phase, List<String> phases, String visibility,
      long startedAt, long phaseEnteredAt, Long nextTransitionAt, Long lastBeatAt, Long endedAt, String summary, boolean sensitive,
      List<Who> people, List<Who> knowers, List<String> constraints) {}

  record LogEntry(String id, long at, String type, String defId, Who actor, Who target, String arcId, int importance, String visibility,
      String source, String status, String text, String recapLine) {}

  record Node(String id, String handle, String name, boolean you, String avatarUrl, String gender, double mood, List<String> circles) {}

  record Edge(String from, String to, String kind, double affinity, double trust, double attraction, double familiarity, double respect,
      boolean connected, List<String> tags) {}

  record Graph(List<Node> nodes, List<Edge> edges) {}

  record EdgeHistory(Who from, Who to, Edge there, Edge back, String summary, List<LogEntry> events) {}

  record MemoryView(String id, long at, String kind, String text, int importance, double score) {}

  record EntityView(String id, String kind, String name, String status, String description) {}

  record DayPoint(String day, double affinity, Double mood) {}

  record Inspector(Who who, String occupation, String livesIn, String relationshipStatus, String bio, String backstory, String lately,
      JsonNode traits, JsonNode traitOffsets, JsonNode temperament, JsonNode quirks, JsonNode interests, JsonNode politics, JsonNode voice,
      JsonNode routine, double mood, double energy, double stress, Double partnerSatisfaction, String location, List<String> constraints,
      List<EntityView> entities, List<MemoryView> memories, Edge impressionOfR, String impressionSummary, List<DayPoint> history) {}

  record Run(String id, String kind, String status, long startedAt, Long finishedAt, Long scriptFrom, Long scriptTo, String fidelity, Integer planned,
      Integer rendered, Integer dropped, Double costUsd, Long rows, String error, List<String> notes) {}

  record Mix(String key, double target, long actual) {}

  record Upcoming(long posts, long comments, long reactions, long dms, List<LogEntry> events) {}

  record BatchView(boolean running, long scriptedUntil, List<Run> runs, List<Mix> eventMix, List<Mix> regions, Upcoming upcoming) {}

  record Drop(long at, String reason) {}

  record Reports(List<Run> continuity, List<Drop> drops, long droppedThisWeek) {}

  record Slice(String key, double usd, long calls) {}

  record Parked(String reason, long jobs, long resumes) {}

  record Costs(double today, double month, List<Slice> byPurpose, List<Slice> byProvider, List<Slice> byModel, List<Slice> perBatch, double cacheHitRate,
      double fallbackRate, long calls, List<LlmConsole.Window> windows, List<Parked> parked) {}

  record PromptRow(String id, long at, String purpose, String alias, String model, String outcome, Double costUsd, Long latencyMs) {}

  record Time(long worldTime, boolean paused, long offsetMs, long committedUntil, long scriptedUntil, boolean backfilling) {}

  record EventDefView(String id, String category, String description, int importance, String otherRole) {}

  record ArcDefView(String id, String category, String description, boolean blacklisted, boolean needsOther, boolean sensitive) {}

  record Injectables(List<EventDefView> events, List<ArcDefView> arcs, List<Who> residents, List<List<Who>> couples) {}

  private static final long DAY = 24 * 3_600_000L;
  private static final int ARCS_SHOWN = 200;

  private final Sql sql;
  private final WorldEngine engine;
  private final ContentLibrary lib;
  private final LlmConsole llm;
  private final Incidents incidents;
  private final app.radiance.kernel.WallClock clock;

  DirectorReads(Sql sql, WorldEngine engine, LlmConsole llm, Incidents incidents, app.radiance.kernel.WallClock clock) {
    this.clock = clock;
    this.sql = sql;
    this.engine = engine;
    this.lib = engine.lib();
    this.llm = llm;
    this.incidents = incidents;
  }

  private Map<String, Who> people() {
    var out = new LinkedHashMap<String, Who>();
    sql.query("SELECT id, handle, display_name, kind, avatar_media_id FROM actor ORDER BY display_name", List.of(), r -> {
      String avatar = r.str("avatar_media_id");
      return new Who(r.str("id"), r.str("handle"), r.str("display_name"), r.str("kind").equals("USER"),
          avatar == null ? null : "/api/media/" + avatar + "?size=thumb");
    }).forEach(w -> out.put(w.id(), w));
    return out;
  }

  private static List<String> strings(JsonNode node) {
    var out = new ArrayList<String>();
    for (var x : node) out.add(x.asString());
    return out;
  }

  // ------------------------------------------------------------------------------------- storylines

  List<ArcView> arcs() {
    var who = people();
    var roles = new HashMap<String, List<Who>>();
    sql.query("SELECT arc_id, actor_id FROM arc_participant", List.of(), r -> new String[] {r.str("arc_id"), r.str("actor_id")})
        .forEach(x -> roles.computeIfAbsent(x[0], k -> new ArrayList<>()).add(who.get(x[1])));
    var knows = new HashMap<String, List<Who>>();
    sql.query("SELECT subject_id, actor_id FROM knowledge WHERE subject_type = 'ARC'", List.of(), r -> new String[] {r.str("subject_id"), r.str("actor_id")})
        .forEach(x -> knows.computeIfAbsent(x[0], k -> new ArrayList<>()).add(who.get(x[1])));
    var rows = new ArrayList<ArcView>();
    for (String status : List.of("ACTIVE", "RESOLVED", "ABANDONED")) {
      rows.addAll(sql.query("SELECT * FROM arc WHERE status = ? ORDER BY started_at DESC LIMIT ?", List.of(status, ARCS_SHOWN), r -> {
        var def = lib.arcs().get(r.str("def_id"));
        String phase = r.str("phase");
        List<String> phases = def == null ? List.of() : def.phases().stream().map(p -> p.id()).toList();
        List<String> constraints = def == null ? List.of()
            : def.phases().stream().filter(p -> p.id().equals(phase)).findFirst().map(p -> p.constraints()).orElse(List.of());
        String id = r.str("id");
        return new ArcView(id, r.str("def_id"), def == null ? null : def.description(), r.str("status"), phase, phases, r.str("visibility"),
            r.lng("started_at"), r.lng("phase_entered_at"), r.isNull("next_transition_at") ? null : r.lng("next_transition_at"),
            r.isNull("last_beat_at") ? null : r.lng("last_beat_at"), r.isNull("ended_at") ? null : r.lng("ended_at"), r.str("summary"),
            def != null && def.sensitive(), roles.getOrDefault(id, List.of()), knows.getOrDefault(id, List.of()), constraints);
      }));
    }
    return rows;
  }

  List<LogEntry> arcEvents(String arcId) {
    var who = people();
    return sql.query("SELECT * FROM event WHERE arc_id = ? ORDER BY world_time LIMIT 200", List.of(arcId), r -> entry(r, who));
  }

  private static LogEntry entry(app.radiance.persistence.Row r, Map<String, Who> who) {
    var payload = Json.tree(r.str("payload_json"));
    String actor = r.str("actor_id"), target = r.str("target_id");
    return new LogEntry(r.str("id"), r.lng("world_time"), r.str("type"), r.str("def_id"), actor == null ? null : who.get(actor), target == null ? null : who.get(target),
        r.str("arc_id"), (int) r.lng("importance"), r.str("visibility"), r.str("source"), r.str("air_status"),
        payload.path("text").asString(null), r.str("recap_line"));
  }

  // ------------------------------------------------------------------------------------------- graph

  Graph graph() {
    var nodes = sql.query("SELECT a.id, a.handle, a.display_name, a.kind, a.avatar_media_id, a.gender, a.circles_json, s.mood FROM actor a"
        + " LEFT JOIN actor_state s ON s.actor_id = a.id ORDER BY a.display_name", List.of(), r -> {
          String avatar = r.str("avatar_media_id");
          return new Node(r.str("id"), r.str("handle"), r.str("display_name"), r.str("kind").equals("USER"),
              avatar == null ? null : "/api/media/" + avatar + "?size=thumb", r.str("gender"), r.isNull("mood") ? 0 : r.dbl("mood"), strings(Json.tree(r.str("circles_json"))));
        });
    var edges = sql.query("SELECT * FROM relationship", List.of(), DirectorReads::edge);
    return new Graph(nodes, edges);
  }

  private static Edge edge(app.radiance.persistence.Row r) {
    return new Edge(r.str("from_id"), r.str("to_id"), r.str("kind"), r.dbl("affinity"), r.dbl("trust"), r.dbl("attraction"), r.dbl("familiarity"),
        r.dbl("respect"), r.bool("connected"), strings(Json.tree(r.str("tags_json"))));
  }

  EdgeHistory edge(String from, String to) {
    var who = people();
    var there = sql.queryOne("SELECT * FROM relationship WHERE from_id = ? AND to_id = ?", List.of(from, to), DirectorReads::edge).orElse(null);
    var back = sql.queryOne("SELECT * FROM relationship WHERE from_id = ? AND to_id = ?", List.of(to, from), DirectorReads::edge).orElse(null);
    String summary = sql.queryOne("SELECT summary FROM relationship WHERE from_id = ? AND to_id = ? AND summary IS NOT NULL", List.of(from, to), r -> r.str("summary")).orElse(null);
    var events = sql.query("SELECT * FROM event WHERE ((actor_id = ? AND target_id = ?) OR (actor_id = ? AND target_id = ?)) AND air_status = 'AIRED'"
        + " ORDER BY world_time DESC LIMIT 40", List.of(from, to, to, from), r -> entry(r, who));
    return new EdgeHistory(who.get(from), who.get(to), there, back, summary, events);
  }

  // ----------------------------------------------------------------------------------------- inspector

  Optional<Inspector> inspector(String handle, long now) {
    var who = people();
    var id = who.values().stream().filter(w -> w.handle().equals(handle) && !w.you()).map(Who::id).findFirst();
    if (id.isEmpty()) return Optional.empty();
    String actor = id.get();
    String user = who.values().stream().filter(Who::you).map(Who::id).findFirst().orElse(null);
    var constraints = new ArrayList<>(sql.query("SELECT tag FROM actor_constraint WHERE actor_id = ?", List.of(actor), r -> r.str("tag")));
    var arcIds = new java.util.HashSet<String>();
    for (var row : sql.query("SELECT a.id, a.def_id, a.phase FROM arc a JOIN arc_participant p ON p.arc_id = a.id WHERE p.actor_id = ? AND a.status = 'ACTIVE'",
        List.of(actor), r -> new String[] {r.str("id"), r.str("def_id"), r.str("phase")})) {
      arcIds.add(row[0]);
      var def = lib.arcs().get(row[1]);
      if (def != null) def.phases().stream().filter(p -> p.id().equals(row[2])).findFirst().ifPresent(p -> constraints.addAll(p.constraints()));
    }
    var entities = sql.query("SELECT id, kind, name, status, visual_description FROM entity WHERE owner_id = ?", List.of(actor),
        r -> new EntityView(r.str("id"), r.str("kind"), r.str("name"), r.str("status"), r.str("visual_description")));
    var participants = user == null ? Set.<String>of() : Set.of(user);
    var memories = new MemoryRepository(sql).of(actor, Math.max(0, now - 90 * DAY)).stream()
        .map(m -> new MemoryView(m.id(), m.worldTime(), m.kind().name(), m.text(), m.importance(), MemoryRetrieval.score(m, now, participants, arcIds, Set.of())))
        .sorted(Comparator.comparingDouble((MemoryView x) -> -x.score())).limit(30).toList();
    Edge impression = user == null ? null : sql.queryOne("SELECT * FROM relationship WHERE from_id = ? AND to_id = ?", List.of(actor, user), DirectorReads::edge).orElse(null);
    String summary = user == null ? null : sql.queryOne("SELECT summary FROM relationship WHERE from_id = ? AND to_id = ? AND summary IS NOT NULL", List.of(actor, user), r -> r.str("summary")).orElse(null);
    var history = sql.query("SELECT day, affinity, mood FROM meter_snapshot WHERE actor_id = ? ORDER BY day DESC LIMIT 30", List.of(actor),
        r -> new DayPoint(r.str("day"), r.dbl("affinity"), r.isNull("mood") ? null : r.dbl("mood")));
    var ordered = new ArrayList<>(history);
    java.util.Collections.reverse(ordered);
    return sql.queryOne("SELECT a.occupation, a.current_city, a.relationship_status, a.bio, p.backstory, p.life_summary, p.traits_json, p.temperament_json,"
        + " p.quirks_json, p.interests_json, p.politics_json, p.voice_json, p.routine_json, s.trait_offsets_json, s.mood, s.energy, s.stress, s.partner_satisfaction, s.location"
        + " FROM actor a JOIN persona p ON p.actor_id = a.id JOIN actor_state s ON s.actor_id = a.id WHERE a.id = ?", List.of(actor),
        r -> new Inspector(who.get(actor), r.str("occupation"), r.str("current_city"), r.str("relationship_status"), r.str("bio"), r.str("backstory"), r.str("life_summary"),
            Json.tree(r.str("traits_json")), Json.tree(r.str("trait_offsets_json")), Json.tree(r.str("temperament_json")), Json.tree(r.str("quirks_json")),
            Json.tree(r.str("interests_json")), Json.tree(r.str("politics_json")), Json.tree(r.str("voice_json")), Json.tree(r.str("routine_json")), r.dbl("mood"), r.dbl("energy"),
            r.dbl("stress"), r.isNull("partner_satisfaction") ? null : r.dbl("partner_satisfaction"), r.str("location"), constraints, entities, memories, impression, summary, ordered));
  }

  // ----------------------------------------------------------------------------------------------- log

  List<LogEntry> events(String source, String status, String actor, String q, Long before, int limit) {
    var who = people();
    var where = new ArrayList<String>();
    var args = new ArrayList<Object>();
    if (source != null && !source.isBlank()) {
      where.add("source = ?");
      args.add(source);
    }
    if (status != null && !status.isBlank()) {
      where.add("air_status = ?");
      args.add(status);
    }
    if (actor != null && !actor.isBlank()) {
      where.add("(actor_id = ? OR target_id = ?)");
      args.add(actor);
      args.add(actor);
    }
    if (q != null && !q.isBlank()) {
      where.add("(type LIKE ? OR def_id LIKE ? OR payload_json LIKE ?)");
      String like = "%" + q.strip().replace("%", "").replace("_", "") + "%";
      args.add(like);
      args.add(like);
      args.add(like);
    }
    if (before != null) {
      where.add("world_time < ?");
      args.add(before);
    }
    args.add(limit);
    return sql.query("SELECT * FROM event" + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where)) + " ORDER BY world_time DESC LIMIT ?", args, r -> entry(r, who));
  }

  Reports reports() {
    var continuity = runs(60).stream().filter(run -> run.notes().stream().anyMatch(n -> n.contains("continuity"))).toList();
    var drops = engine.recentDrops().stream().map(d -> new Drop(d.at(), d.reason())).toList();
    long week = engine.locked(w -> w.time) - 7 * DAY;
    long dropped = sql.queryOne("SELECT COUNT(*) AS n FROM post WHERE created_at >= ? AND air_status = 'DROPPED'", List.of(week), r -> r.lng("n")).orElse(0L)
        + sql.queryOne("SELECT COUNT(*) AS n FROM comment WHERE created_at >= ? AND air_status = 'DROPPED'", List.of(week), r -> r.lng("n")).orElse(0L);
    return new Reports(continuity, drops, dropped);
  }

  // --------------------------------------------------------------------------------------------- batch

  private List<Run> runs(int limit) {
    return sql.query("SELECT * FROM batch_run ORDER BY started_at DESC LIMIT ?", List.of(limit), r -> {
      var notes = new ArrayList<String>();
      String report = r.str("report_json");
      if (report != null) notes.addAll(strings(Json.tree(report).path("notes")));
      return new Run(r.str("id"), r.str("kind"), r.str("status"), r.lng("started_at"), r.isNull("finished_at") ? null : r.lng("finished_at"),
          r.isNull("script_from") ? null : r.lng("script_from"), r.isNull("script_to") ? null : r.lng("script_to"), r.str("fidelity"),
          r.isNull("items_planned") ? null : (int) r.lng("items_planned"), r.isNull("items_rendered") ? null : (int) r.lng("items_rendered"),
          r.isNull("items_dropped") ? null : (int) r.lng("items_dropped"), r.isNull("cost_usd") ? null : r.dbl("cost_usd"),
          r.isNull("rows_written") ? null : r.lng("rows_written"), r.str("error"), notes);
    });
  }

  BatchView batch(long now, boolean running) {
    var who = people();
    long from = now - 7 * DAY;
    var mix = new ArrayList<Mix>();
    var events = new HashMap<String, Long>();
    sql.query("SELECT json_extract(payload_json, '$.category') AS c, COUNT(*) AS n FROM event WHERE world_time >= ? AND world_time <= ? AND air_status = 'AIRED' GROUP BY c",
        List.of(from, now), r -> Map.entry(String.valueOf(r.str("c")), r.lng("n"))).forEach(e -> events.put(e.getKey(), e.getValue()));
    engine.knobs().eventMix().forEach((k, v) -> mix.add(new Mix(k, v, events.getOrDefault(k, 0L))));
    var regions = new ArrayList<Mix>();
    var byRegion = new HashMap<String, Long>();
    sql.query("SELECT h.region AS region, COUNT(*) AS n FROM post p JOIN headline h ON h.id = p.headline_id WHERE p.created_at >= ? AND p.created_at <= ? AND p.air_status = 'AIRED'"
        + " AND h.region IS NOT NULL GROUP BY h.region", List.of(from, now), r -> Map.entry(r.str("region"), r.lng("n"))).forEach(e -> byRegion.put(e.getKey(), e.getValue()));
    engine.knobs().regionMix().forEach((k, v) -> regions.add(new Mix(k, v, byRegion.getOrDefault(k, 0L))));
    var soon = sql.query("SELECT * FROM event WHERE air_status = 'SCRIPTED' ORDER BY world_time LIMIT 400", List.of(), r -> entry(r, who)).stream()
        .sorted(Comparator.comparingInt(LogEntry::importance).reversed().thenComparingLong(LogEntry::at)).limit(30)
        .sorted(Comparator.comparingLong(LogEntry::at)).toList();
    var upcoming = new Upcoming(scripted("post"), scripted("comment"), scripted("reaction"), scripted("dm_message"), soon);
    long until = sql.queryOne("SELECT scripted_until FROM world", List.of(), r -> r.lng("scripted_until")).orElse(0L);
    return new BatchView(running, until, runs(14), mix, regions, upcoming);
  }

  private long scripted(String table) {
    return sql.queryOne("SELECT COUNT(*) AS n FROM " + table + " WHERE air_status = 'SCRIPTED'", List.of(), r -> r.lng("n")).orElse(0L);
  }

  // --------------------------------------------------------------------------------------------- costs

  Costs costs() {
    long wall = clock.nowMs();
    var zone = sql.queryOne("SELECT timezone FROM world", List.of(), r -> ZoneId.of(r.str("timezone"))).orElse(ZoneId.of("America/Chicago"));
    var date = Instant.ofEpochMilli(wall).atZone(zone).toLocalDate();
    long today = date.atStartOfDay(zone).toInstant().toEpochMilli();
    long month = date.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli();
    var perBatch = runs(14).stream().filter(run -> run.costUsd() != null)
        .map(run -> new Slice(Instant.ofEpochMilli(run.startedAt()).atZone(zone).toLocalDate() + " " + run.kind(), run.costUsd(), run.planned() == null ? 0 : run.planned())).toList();
    double[] totals = sql.queryOne("SELECT COUNT(*) AS n, COALESCE(SUM(cache_read_tokens), 0) AS c, COALESCE(SUM(input_tokens), 0) AS i,"
        + " COALESCE(SUM(CASE WHEN attempt > 1 THEN 1 ELSE 0 END), 0) AS f FROM llm_call WHERE created_at >= ?", List.of(month),
        r -> new double[] {r.dbl("n"), r.dbl("c"), r.dbl("i"), r.dbl("f")}).orElse(new double[4]);
    double read = totals[1] + totals[2];
    var parked = sql.query("SELECT wait_reason, COUNT(*) AS n, MIN(run_after) AS r FROM job WHERE status = 'WAITING' GROUP BY wait_reason", List.of(),
        r -> new Parked(r.str("wait_reason"), r.lng("n"), r.lng("r")));
    return new Costs(spent(today), spent(month), sliced("purpose", month), sliced("provider", month), sliced("model", month), perBatch,
        read == 0 ? 0 : totals[1] / read, totals[0] == 0 ? 0 : totals[3] / totals[0], (long) totals[0], llm.windows(), parked);
  }

  private double spent(long since) {
    return sql.queryOne("SELECT COALESCE(SUM(cost_usd), 0) AS c FROM llm_call WHERE created_at >= ?", List.of(since), r -> r.dbl("c")).orElse(0.0);
  }

  private List<Slice> sliced(String column, long since) {
    return sql.query("SELECT " + column + " AS k, COALESCE(SUM(cost_usd), 0) AS c, COUNT(*) AS n FROM llm_call WHERE created_at >= ? GROUP BY " + column + " ORDER BY c DESC, n DESC",
        List.of(since), r -> new Slice(r.str("k"), r.dbl("c"), r.lng("n")));
  }

  // ------------------------------------------------------------------------------------------- prompts

  List<PromptRow> prompts(String purpose, String q, int limit) {
    var args = new ArrayList<Object>();
    String where = "prompt_ref IS NOT NULL";
    if (purpose != null && !purpose.isBlank()) {
      where += " AND purpose = ?";
      args.add(purpose);
    }
    boolean searching = q != null && !q.isBlank();
    args.add(searching ? 300 : limit);
    var rows = sql.query("SELECT id, purpose, alias, model, outcome, cost_usd, latency_ms, created_at, prompt_ref FROM llm_call WHERE " + where + " ORDER BY created_at DESC LIMIT ?", args,
        r -> Map.entry(r.str("prompt_ref"), new PromptRow(r.str("id"), r.lng("created_at"), r.str("purpose"), r.str("alias"), r.str("model"), r.str("outcome"),
            r.isNull("cost_usd") ? null : r.dbl("cost_usd"), r.isNull("latency_ms") ? null : r.lng("latency_ms"))));
    return rows.stream().filter(e -> !searching || llm.prompt(e.getKey()).map(text -> text.contains(q.strip())).orElse(false))
        .map(Map.Entry::getValue).limit(limit).toList();
  }

  Optional<String> prompt(String id) {
    return sql.queryOne("SELECT prompt_ref FROM llm_call WHERE id = ? AND prompt_ref IS NOT NULL", List.of(id), r -> r.str("prompt_ref")).flatMap(llm::prompt);
  }

  // ------------------------------------------------------------------------------------------ time etc

  Time time(long now, boolean backfilling) {
    return sql.queryOne("SELECT paused, time_offset_ms, committed_until, scripted_until FROM world", List.of(),
        r -> new Time(now, r.bool("paused"), r.lng("time_offset_ms"), r.lng("committed_until"), r.lng("scripted_until"), backfilling))
        .orElse(new Time(now, false, 0, 0, 0, backfilling));
  }

  Injectables injectables() {
    var who = people();
    var events = lib.events().values().stream().sorted(Comparator.comparing(e -> e.id()))
        .map(e -> new EventDefView(e.id(), e.category().name(), e.description(), e.importance(), e.participants().isEmpty() ? null : e.participants().keySet().iterator().next())).toList();
    var arcs = lib.arcs().values().stream().sorted(Comparator.comparing(a -> a.id()))
        .map(a -> new ArcDefView(a.id(), a.category().name(), a.description(), lib.isBlacklisted(a.id()), a.roles().size() > 1, a.sensitive())).toList();
    var residents = who.values().stream().filter(w -> !w.you()).toList();
    var couples = incidents.couples().stream().map(c -> List.of(who.get(c.a()), who.get(c.b()))).toList();
    return new Injectables(events, arcs, residents, couples);
  }
}
