package app.radiance.memory;

import static app.radiance.persistence.Table.col;

import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.Collection;
import java.util.List;

public final class MemoryRepository {

  static final Table<MemoryRow> TABLE =
      new Table<>(
          "memory",
          List.of("id"),
          r ->
              new MemoryRow(
                  r.str("id"), r.str("owner_id"), r.lng("world_time"), r.enm("kind", MemoryRow.Kind.class),
                  r.str("text"), (int) r.lng("importance"), r.str("participants_json"), r.str("tags_json"),
                  r.str("arc_id"), r.str("event_id"), r.bool("compacted")),
          col("id", MemoryRow::id),
          col("owner_id", MemoryRow::ownerId),
          col("world_time", MemoryRow::worldTime),
          col("kind", MemoryRow::kind),
          col("text", MemoryRow::text),
          col("importance", MemoryRow::importance),
          col("participants_json", MemoryRow::participantsJson),
          col("tags_json", MemoryRow::tagsJson),
          col("arc_id", MemoryRow::arcId),
          col("event_id", MemoryRow::eventId),
          col("compacted", MemoryRow::compacted));

  private final Sql sql;

  public MemoryRepository(Sql sql) {
    this.sql = sql;
  }

  /** An owner's uncompacted memories since {@code from}, oldest first (uses {@code ix_memory_owner}). */
  public List<MemoryRow> of(String ownerId, long from) {
    return TABLE.where(sql, "owner_id = ? AND compacted = 0 AND world_time >= ? ORDER BY world_time, id",
        ownerId, from);
  }

  /** Every memory carrying a tag such as {@code clash}, for counters the engine keeps in memory. */
  public List<MemoryRow> tagged(String tag) {
    return TABLE.where(sql, "tags_json LIKE ? ORDER BY world_time, id", "%\"" + tag + "%");
  }

  public UnitOfWork save(UnitOfWork uow, Collection<MemoryRow> rows) {
    return TABLE.insert(uow, rows);
  }
}
