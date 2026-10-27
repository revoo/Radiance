package app.radiance.story;

import static app.radiance.persistence.Table.col;

import app.radiance.kernel.Visibility;
import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.Collection;
import java.util.List;

/** Arcs and their participants. Active arcs live in the world model; ended ones stay for the console. */
public final class ArcRepository {

  static final Table<ArcRow> ARCS =
      new Table<>(
          "arc",
          List.of("id"),
          r ->
              new ArcRow(
                  r.str("id"),
                  r.str("def_id"),
                  r.enm("status", ArcRow.Status.class),
                  r.str("phase"),
                  r.enm("visibility", Visibility.class),
                  r.lng("started_at"),
                  r.lng("phase_entered_at"),
                  r.lngOrNull("next_transition_at"),
                  r.lngOrNull("last_beat_at"),
                  r.str("data_json"),
                  r.str("summary"),
                  r.lngOrNull("ended_at"),
                  r.str("end_event_id")),
          col("id", ArcRow::id),
          col("def_id", ArcRow::defId),
          col("status", ArcRow::status),
          col("phase", ArcRow::phase),
          col("visibility", ArcRow::visibility),
          col("started_at", ArcRow::startedAt),
          col("phase_entered_at", ArcRow::phaseEnteredAt),
          col("next_transition_at", ArcRow::nextTransitionAt),
          col("last_beat_at", ArcRow::lastBeatAt),
          col("data_json", ArcRow::dataJson),
          col("summary", ArcRow::summary),
          col("ended_at", ArcRow::endedAt),
          col("end_event_id", ArcRow::endEventId));

  static final Table<ArcRow.Participant> PARTICIPANTS =
      new Table<>(
          "arc_participant",
          List.of("arc_id", "actor_id"),
          r -> new ArcRow.Participant(r.str("arc_id"), r.str("actor_id"), r.str("role")),
          col("arc_id", ArcRow.Participant::arcId),
          col("actor_id", ArcRow.Participant::actorId),
          col("role", ArcRow.Participant::role));

  private final Sql sql;

  public ArcRepository(Sql sql) {
    this.sql = sql;
  }

  public List<ArcRow> active() {
    return ARCS.where(sql, "status = ?", ArcRow.Status.ACTIVE);
  }

  public List<ArcRow> all() {
    return ARCS.all(sql);
  }

  public List<ArcRow.Participant> participants() {
    return PARTICIPANTS.all(sql);
  }

  public UnitOfWork save(UnitOfWork uow, Collection<ArcRow> arcs) {
    return ARCS.upsert(uow, arcs);
  }

  /** Arcs first: participants reference them. */
  public UnitOfWork saveParticipants(UnitOfWork uow, Collection<ArcRow.Participant> rows) {
    return PARTICIPANTS.upsert(uow, rows);
  }
}
