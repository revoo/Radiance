package app.radiance.story;

import static app.radiance.persistence.Table.col;

import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.Collection;
import java.util.List;

public final class ConstraintRepository {

  static final Table<ConstraintRow> TABLE =
      new Table<>(
          "actor_constraint",
          List.of("actor_id", "tag"),
          r ->
              new ConstraintRow(
                  r.str("actor_id"), r.str("tag"), r.lng("since"), r.lngOrNull("until"),
                  r.str("source_event_id")),
          col("actor_id", ConstraintRow::actorId),
          col("tag", ConstraintRow::tag),
          col("since", ConstraintRow::since),
          col("until", ConstraintRow::until),
          col("source_event_id", ConstraintRow::sourceEventId));

  private final Sql sql;

  public ConstraintRepository(Sql sql) {
    this.sql = sql;
  }

  public List<ConstraintRow> all() {
    return TABLE.all(sql);
  }

  public UnitOfWork save(UnitOfWork uow, Collection<ConstraintRow> rows) {
    return TABLE.upsert(uow, rows);
  }

  public UnitOfWork delete(UnitOfWork uow, String actorId, String tag) {
    return uow.add("DELETE FROM actor_constraint WHERE actor_id = ? AND tag = ?", actorId, tag);
  }
}
