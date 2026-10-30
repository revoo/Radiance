package app.radiance.messenger;

import static app.radiance.persistence.Table.col;

import app.radiance.kernel.AirStatus;
import app.radiance.kernel.RenderStatus;
import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

  public List<DmRow.Message> scriptedUntil(long t) {
    return MESSAGES.where(sql, "air_status = 'SCRIPTED' AND created_at <= ? ORDER BY created_at, id", t);
  }

  public List<DmRow.Message> messages(String threadId) {
    return MESSAGES.where(sql, "thread_id = ? ORDER BY created_at, id", threadId);
  }

  public UnitOfWork saveThreads(UnitOfWork uow, Collection<DmRow.Thread> rows) {
    return THREADS.upsert(uow, rows);
  }

  /**
   * Threads a script or live play starts: inserted if missing, never overwritten, so an existing
   * thread keeps its summary.
   */
  public UnitOfWork ensureThreads(UnitOfWork uow, Collection<DmRow.Thread> rows) {
    for (var t : rows) {
      uow.add("INSERT INTO dm_thread (id, a_id, b_id) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING", t.id(), t.aId(), t.bId());
    }
    return uow;
  }

  public Optional<DmRow.Thread> thread(String id) {
    return THREADS.where(sql, "id = ?", id).stream().findFirst();
  }

  /** The thread between two actors, whichever order they're given in. */
  public Optional<DmRow.Thread> between(String x, String y) {
    String a = x.compareTo(y) < 0 ? x : y, b = a.equals(x) ? y : x;
    return THREADS.where(sql, "a_id = ? AND b_id = ?", a, b).stream().findFirst();
  }

  public UnitOfWork summarize(UnitOfWork uow, String threadId, String summary) {
    return uow.add("UPDATE dm_thread SET summary = ? WHERE id = ?", summary, threadId);
  }

  public UnitOfWork saveMessages(UnitOfWork uow, Collection<DmRow.Message> rows) {
    return MESSAGES.upsert(uow, rows);
  }
}
