package app.radiance.messenger;

import static app.radiance.persistence.Table.col;

import app.radiance.kernel.AirStatus;
import app.radiance.kernel.RenderStatus;
import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.Collection;
import java.util.List;

public final class DmRepository {

  static final Table<DmRow.Thread> THREADS =
      new Table<>(
          "dm_thread",
          List.of("id"),
          r -> new DmRow.Thread(r.str("id"), r.str("a_id"), r.str("b_id"), r.str("summary"),
              r.lngOrNull("last_message_at")),
          col("id", DmRow.Thread::id),
          col("a_id", DmRow.Thread::aId),
          col("b_id", DmRow.Thread::bId),
          col("summary", DmRow.Thread::summary),
          col("last_message_at", DmRow.Thread::lastMessageAt));

  static final Table<DmRow.Message> MESSAGES =
      new Table<>(
          "dm_message",
          List.of("id"),
          r -> new DmRow.Message(r.str("id"), r.str("thread_id"), r.str("sender_id"), r.lng("created_at"),
              r.enm("air_status", AirStatus.class), r.str("body"), r.enm("render_status", RenderStatus.class),
              r.str("brief_json"), r.str("appraisal_json"), r.lngOrNull("read_at")),
          col("id", DmRow.Message::id),
          col("thread_id", DmRow.Message::threadId),
          col("sender_id", DmRow.Message::senderId),
          col("created_at", DmRow.Message::createdAt),
          col("air_status", DmRow.Message::airStatus),
          col("body", DmRow.Message::body),
          col("render_status", DmRow.Message::renderStatus),
          col("brief_json", DmRow.Message::briefJson),
          col("appraisal_json", DmRow.Message::appraisalJson),
          col("read_at", DmRow.Message::readAt));

  private final Sql sql;

  public DmRepository(Sql sql) {
    this.sql = sql;
  }

  public List<DmRow.Thread> threads() {
    return THREADS.all(sql);
  }

  /** The pending script, in air-time order (uses {@code ix_dm_script}). */
  public List<DmRow.Message> scripted() {
    return MESSAGES.where(sql, "air_status = 'SCRIPTED' ORDER BY created_at, id");
  }

  public List<DmRow.Message> messages(String threadId) {
    return MESSAGES.where(sql, "thread_id = ? ORDER BY created_at, id", threadId);
  }

  public UnitOfWork saveThreads(UnitOfWork uow, Collection<DmRow.Thread> rows) {
    return THREADS.upsert(uow, rows);
  }

  public UnitOfWork saveMessages(UnitOfWork uow, Collection<DmRow.Message> rows) {
    return MESSAGES.upsert(uow, rows);
  }
}
