package app.radiance.social;

import static app.radiance.persistence.Table.col;

import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.Collection;
import java.util.List;

public final class NotificationRepository {

  static final Table<NotificationRow> TABLE =
      new Table<>(
          "notification",
          List.of("id"),
          r ->
              new NotificationRow(
                  r.str("id"), r.str("kind"), r.str("actor_id"), r.str("ref_type"), r.str("ref_id"),
                  r.lng("created_at"), r.lngOrNull("read_at")),
          col("id", NotificationRow::id),
          col("kind", NotificationRow::kind),
          col("actor_id", NotificationRow::actorId),
          col("ref_type", NotificationRow::refType),
          col("ref_id", NotificationRow::refId),
          col("created_at", NotificationRow::createdAt),
          col("read_at", NotificationRow::readAt));

  private final Sql sql;

  public NotificationRepository(Sql sql) {
    this.sql = sql;
  }

  /** Notifications created in {@code [from, to)}, oldest first (uses {@code ix_notif_time}). */
  public List<NotificationRow> between(long from, long to) {
    return TABLE.where(sql, "created_at >= ? AND created_at < ? ORDER BY created_at, id", from, to);
  }

  public UnitOfWork save(UnitOfWork uow, Collection<NotificationRow> rows) {
    return TABLE.insert(uow, rows);
  }
}
