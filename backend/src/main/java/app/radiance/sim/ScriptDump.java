package app.radiance.sim;

import app.radiance.persistence.Sql;
import app.radiance.sim.model.SimWorld;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A plain-text dump of one world-day's script (SPEC §12 M4): every event, post, comment and DM by air
 * time with its brief, and reactions as counts per item. Briefs only; no prose exists yet.
 */
public final class ScriptDump {

  private ScriptDump() {}

  public static String day(Sql sql, SimWorld w, LocalDate date) {
    long from = date.atStartOfDay(w.zone).toInstant().toEpochMilli();
    long to = date.plusDays(1).atStartOfDay(w.zone).toInstant().toEpochMilli();
    record Line(long t, String text) {}
    var lines = new ArrayList<Line>();
    var reactions = new HashMap<String, Map<String, Integer>>();
    sql.query("SELECT target_id, kind FROM reaction WHERE created_at >= ? AND created_at < ?", List.of(from, to),
        r -> new String[] {r.str("target_id"), r.str("kind")}).forEach(x ->
        reactions.computeIfAbsent(x[0], k -> new java.util.TreeMap<>()).merge(x[1], 1, Integer::sum));
    sql.query("SELECT world_time, type, def_id, actor_id, visibility, air_status, payload_json FROM event"
        + " WHERE world_time >= ? AND world_time < ? ORDER BY world_time, id", List.of(from, to), r -> {
          var p = EventPayload.parse(r.str("payload_json"));
          String arc = p.arc() == null ? "" : " [" + p.arc().defId() + ": " + (p.arc().fromPhase() == null ? "" : p.arc().fromPhase()
              + " → ") + p.arc().toPhase() + "]";
          return new Line(r.lng("world_time"), "EVENT " + r.str("type") + arc + " " + who(w, r.str("actor_id")) + " ("
              + r.str("visibility").toLowerCase() + ", " + r.str("air_status").toLowerCase() + ") — " + p.text());
        }).forEach(lines::add);
    sql.query("SELECT id, created_at, author_id, kind, origin, air_status, brief_json FROM post"
        + " WHERE created_at >= ? AND created_at < ? ORDER BY created_at, id", List.of(from, to), r -> {
          var b = Brief.parse(r.str("brief_json"));
          return new Line(r.lng("created_at"), "POST " + r.str("kind") + " " + who(w, r.str("author_id")) + " ["
              + (b.category() == null ? r.str("origin").toLowerCase() : b.category()) + (b.stance() == null ? "" : ", " + b.stance())
              + "] " + (b.text() == null ? "" : b.text()) + headline(b) + react(reactions, r.str("id"))
              + (r.str("air_status").equals("DROPPED") ? "  ⟂ dropped" : ""));
        }).forEach(lines::add);
    sql.query("SELECT id, created_at, author_id, parent_id, air_status, brief_json FROM comment"
        + " WHERE created_at >= ? AND created_at < ? ORDER BY created_at, id", List.of(from, to), r -> {
          var b = Brief.parse(r.str("brief_json"));
          return new Line(r.lng("created_at"), "    " + (r.str("parent_id") == null ? "COMMENT " : "  REPLY ")
              + who(w, r.str("author_id")) + " → " + who(w, b.toward()) + " " + b.stance()
              + react(reactions, r.str("id")) + (r.str("air_status").equals("DROPPED") ? "  ⟂ dropped" : ""));
        }).forEach(lines::add);
    sql.query("SELECT created_at, sender_id, brief_json FROM dm_message WHERE created_at >= ? AND created_at < ?"
        + " ORDER BY created_at, id", List.of(from, to), r -> {
          var b = Brief.parse(r.str("brief_json"));
          return new Line(r.lng("created_at"), "DM " + who(w, r.str("sender_id")) + " → " + who(w, b.toward()) + " — "
              + b.text());
        }).forEach(lines::add);
    lines.sort(java.util.Comparator.comparingLong(Line::t));
    var sb = new StringBuilder("Script for " + date + " (" + w.zone + "), " + lines.size() + " lines\n\n");
    for (var l : lines) {
      var time = Instant.ofEpochMilli(l.t()).atZone(w.zone).toLocalTime().withNano(0);
      sb.append(time).append("  ").append(l.text()).append('\n');
    }
    return sb.toString();
  }

  private static String who(SimWorld w, String id) {
    if (id == null) return "-";
    var p = w.people.get(id);
    return p == null ? id : "@" + p.handle;
  }

  private static String headline(Brief b) {
    Object h = b.extra().get("headline");
    return h == null ? "" : " — “" + h + "”";
  }

  private static String react(Map<String, Map<String, Integer>> reactions, String id) {
    var r = reactions.get(id);
    return r == null ? "" : "  " + r;
  }
}
