package app.radiance.story;

import static app.radiance.persistence.Table.col;

import app.radiance.kernel.AirStatus;
import app.radiance.kernel.Visibility;
import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The event log. Rows change only to air, drop or gain a recap line, so saves are upserts; nothing is
 * ever deleted.
 */
public final class EventRepository {

  static final Table<EventRow> TABLE =
      new Table<>(
          "event",
          List.of("id"),
          r ->
              new EventRow(
                  r.str("id"),
                  r.lng("world_time"),
                  r.str("type"),
                  r.str("def_id"),
                  r.str("actor_id"),
                  r.str("target_id"),
                  r.str("arc_id"),
                  (int) r.lng("importance"),
                  r.enm("visibility", Visibility.class),
                  r.enm("source", EventRow.Source.class),
                  r.enm("air_status", AirStatus.class),
                  r.str("payload_json"),
                  r.str("recap_line")),
          col("id", EventRow::id),
          col("world_time", EventRow::worldTime),
          col("type", EventRow::type),
          col("def_id", EventRow::defId),
          col("actor_id", EventRow::actorId),
          col("target_id", EventRow::targetId),
          col("arc_id", EventRow::arcId),
          col("importance", EventRow::importance),
          col("visibility", EventRow::visibility),
          col("source", EventRow::source),
          col("air_status", EventRow::airStatus),
          col("payload_json", EventRow::payloadJson),
          col("recap_line", EventRow::recapLine));

  private final Sql sql;

  public EventRepository(Sql sql) {
    this.sql = sql;
  }

  public Optional<EventRow> find(String id) {
    return TABLE.one(sql, "id = ?", id);
  }

  /** The pending script, in air-time order (uses {@code ix_event_script}). */
  public List<EventRow> scripted() {
    return TABLE.where(sql, "air_status = 'SCRIPTED' ORDER BY world_time, id");
  }

  /** Scripted events due by {@code t}, in air-time order. */
  public List<EventRow> scriptedUntil(long t) {
    return TABLE.where(sql, "air_status = 'SCRIPTED' AND world_time <= ? ORDER BY world_time, id", t);
  }

  public List<EventRow> airedBetween(long fromInclusive, long toExclusive) {
    return TABLE.where(
        sql,
        "world_time >= ? AND world_time < ? AND air_status = 'AIRED' ORDER BY world_time, id",
        fromInclusive,
        toExclusive);
  }

  public UnitOfWork save(UnitOfWork uow, Collection<EventRow> rows) {
    return TABLE.upsert(uow, rows);
  }
}
