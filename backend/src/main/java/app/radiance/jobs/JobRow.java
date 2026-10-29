package app.radiance.jobs;

/**
 * A durable unit of non-live work (SPEC §5.15): render a thread, compact memories, run a bake-off
 * fixture. Times are wall time (ADR 0010). Lower {@code priority} runs first.
 *
 * @param waitReason the budget window a WAITING job is parked on
 */
public record JobRow(
    String id, String kind, String refId, int priority, Status status, int attempts, long runAfter,
    String waitReason, Long lockedAt, String lastError, long createdAt) {

  public enum Status {
    PENDING,
    RUNNING,
    WAITING,
    DONE,
    FAILED
  }
}
