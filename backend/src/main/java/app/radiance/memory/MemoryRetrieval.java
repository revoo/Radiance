package app.radiance.memory;

import app.radiance.kernel.Json;
import app.radiance.persistence.Sql;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Memory retrieval (SPEC §5.7): a SQL pre-filter, then scoring in Java —
 * {@code 0.5·recency (half-life 7 world-days) + 0.3·importance/10 + 0.2·relevance}, where relevance
 * is participant overlap plus tag and arc overlap. Top {@code n} per actor per packet.
 */
public final class MemoryRetrieval {

  static final long DAY = 86_400_000L;

  public record Scored(MemoryRow memory, double score) {}

  private final Sql sql;

  public MemoryRetrieval(Sql sql) {
    this.sql = sql;
  }

  public List<MemoryRow> top(String owner, long now, Set<String> participants, Set<String> arcs, Set<String> tags, int n) {
    // pre-filter: uncompacted, at or before now, and plausibly relevant (bounded at 200 rows)
    var rows = MemoryRepository.TABLE.where(sql, "owner_id = ? AND compacted = 0 AND world_time <= ?"
        + " AND (importance >= 7 OR world_time >= ? OR arc_id IS NOT NULL OR participants_json <> '[]')"
        + " ORDER BY world_time DESC LIMIT 200", owner, now, now - 3 * DAY);
    var scored = new ArrayList<Scored>();
    for (var m : rows) scored.add(new Scored(m, score(m, now, participants, arcs, tags)));
    scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(s -> s.memory().id()));
    return scored.stream().limit(n).map(Scored::memory).toList();
  }

  public static double score(MemoryRow m, long now, Set<String> participants, Set<String> arcs, Set<String> tags) {
    double recency = Math.pow(0.5, Math.max(0, now - m.worldTime()) / (7.0 * DAY));
    double overlap = 0;
    int people = 0;
    for (var p : Json.tree(m.participantsJson())) {
      people++;
      if (participants.contains(p.asString())) overlap++;
    }
    double relevance = (people == 0 ? 0 : overlap / people) * 0.6;
    for (var t : Json.tree(m.tagsJson())) if (tags.contains(t.asString())) relevance += 0.2;
    if (m.arcId() != null && arcs.contains(m.arcId())) relevance += 0.2;
    return 0.5 * recency + 0.3 * m.importance() / 10.0 + 0.2 * Math.min(1, relevance);
  }
}
