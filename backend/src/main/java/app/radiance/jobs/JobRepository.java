package app.radiance.jobs;

import static app.radiance.persistence.Table.col;

import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Ulid;
import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.List;
import java.util.Optional;

public final class JobRepository {

  static final Table<JobRow> TABLE =
      new Table<>(
          "job",
          List.of("id"),
          r -> new JobRow(r.str("id"), r.str("kind"), r.str("ref_id"), (int) r.lng("priority"),
              r.enm("status", JobRow.Status.class), (int) r.lng("attempts"), r.lng("run_after"), r.str("wait_reason"),
              r.lngOrNull("locked_at"), r.str("last_error"), r.lng("created_at")),
          col("id", JobRow::id),
          col("kind", JobRow::kind),
          col("ref_id", JobRow::refId),
          col("priority", JobRow::priority),
          col("status", JobRow::status),
          col("attempts", JobRow::attempts),
          col("run_after", JobRow::runAfter),
          col("wait_reason", JobRow::waitReason),
          col("locked_at", JobRow::lockedAt),
          col("last_error", JobRow::lastError),
          col("created_at", JobRow::createdAt));

  private final Sql sql;

  public JobRepository(Sql sql) {
    this.sql = sql;
  }

  /**
   * Adds a job unless one for the same kind and ref is already active ({@code ux_job_active}), so
   * enqueueing twice — a re-run batch, a retried unit — changes nothing.
   */
  public UnitOfWork enqueue(UnitOfWork uow, String kind, String refId, int priority, long runAfter, long now) {
    // job ids are operational, not part of any plan: a nonce lets a finished job be enqueued again
    String id = Ulid.next(now, RandomSource.seeded(System.nanoTime()).derive("job:" + kind + ":" + refId));
    return uow.add("INSERT INTO job (id, kind, ref_id, priority, status, attempts, run_after, created_at)"
        + " VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?) ON CONFLICT DO NOTHING", id, kind, refId, priority, runAfter, now);
  }

  /** Ready jobs, best first (uses {@code ix_job_ready}). */
  public List<JobRow> ready(long now, int limit) {
    return TABLE.where(sql, "status = 'PENDING' AND run_after <= ? ORDER BY priority, run_after, id LIMIT ?", now, limit);
  }

  public Optional<JobRow> find(String id) {
    return TABLE.one(sql, "id = ?", id);
  }

  public Optional<JobRow> active(String kind, String refId) {
    return TABLE.one(sql, "kind = ? AND ref_id = ? AND status IN ('PENDING', 'RUNNING', 'WAITING')", kind, refId);
  }

  public List<JobRow> byStatus(JobRow.Status status) {
    return TABLE.where(sql, "status = ? ORDER BY priority, run_after, id", status);
  }

  public long count(JobRow.Status status) {
    return sql.queryOne("SELECT COUNT(*) AS n FROM job WHERE status = ?", List.of(status.name()), r -> r.lng("n"))
        .orElse(0L);
  }

  /** Claims a PENDING job; returns false if another runner got it first. */
  public UnitOfWork claim(UnitOfWork uow, String id, long now) {
    return uow.add("UPDATE job SET status = 'RUNNING', locked_at = ?, attempts = attempts + 1 WHERE id = ?"
        + " AND status = 'PENDING'", now, id);
  }

  public UnitOfWork done(UnitOfWork uow, String id) {
    return uow.add("UPDATE job SET status = 'DONE', locked_at = NULL, last_error = NULL WHERE id = ?", id);
  }

  /** Parked until a budget window resets (SPEC §5.15). */
  public UnitOfWork park(UnitOfWork uow, String id, String window, long resumeAt) {
    return uow.add("UPDATE job SET status = 'WAITING', wait_reason = ?, run_after = ?, locked_at = NULL,"
        + " attempts = attempts - 1 WHERE id = ?", window, resumeAt, id);
  }

  public UnitOfWork retry(UnitOfWork uow, String id, long runAfter, String error) {
    return uow.add("UPDATE job SET status = 'PENDING', run_after = ?, locked_at = NULL, last_error = ? WHERE id = ?",
        runAfter, truncate(error), id);
  }

  public UnitOfWork fail(UnitOfWork uow, String id, String error) {
    return uow.add("UPDATE job SET status = 'FAILED', locked_at = NULL, last_error = ? WHERE id = ?", truncate(error), id);
  }

  /** Parked jobs whose window has reset go back in line; jobs a crashed runner held do too. */
  public UnitOfWork wake(UnitOfWork uow, long now, long staleBefore) {
    uow.add("UPDATE job SET status = 'PENDING', wait_reason = NULL WHERE status = 'WAITING' AND run_after <= ?", now);
    return uow.add("UPDATE job SET status = 'PENDING', locked_at = NULL WHERE status = 'RUNNING' AND locked_at < ?",
        staleBefore);
  }

  private static String truncate(String s) {
    return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
  }
}
