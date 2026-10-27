package app.radiance.sim;

import static app.radiance.persistence.Table.col;

import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.Collection;
import java.util.List;

public final class ScheduledIntentRepository {

  static final Table<ScheduledIntentRow> TABLE =
      new Table<>(
          "scheduled_intent",
          List.of("id"),
          r ->
              new ScheduledIntentRow(
                  r.str("id"),
                  r.lng("due_at"),
                  r.str("actor_id"),
                  r.str("kind"),
                  r.str("target_type"),
                  r.str("target_id"),
                  r.str("payload_json"),
                  r.enm("status", ScheduledIntentRow.Status.class),
                  r.str("cause_event_id")),
          col("id", ScheduledIntentRow::id),
          col("due_at", ScheduledIntentRow::dueAt),
          col("actor_id", ScheduledIntentRow::actorId),
          col("kind", ScheduledIntentRow::kind),
          col("target_type", ScheduledIntentRow::targetType),
          col("target_id", ScheduledIntentRow::targetId),
          col("payload_json", ScheduledIntentRow::payloadJson),
          col("status", ScheduledIntentRow::status),
          col("cause_event_id", ScheduledIntentRow::causeEventId));

  private final Sql sql;

  public ScheduledIntentRepository(Sql sql) {
    this.sql = sql;
  }

  /** Pending intents in due order (uses {@code ix_intent_due}). */
  public List<ScheduledIntentRow> pending() {
    return TABLE.where(sql, "status = 'PENDING' ORDER BY due_at, id");
  }

  public UnitOfWork save(UnitOfWork uow, Collection<ScheduledIntentRow> rows) {
    return TABLE.upsert(uow, rows);
  }
}
