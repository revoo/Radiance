package app.radiance.llm;

/**
 * A call didn't fit a spending or quota window. Nothing was sent. Non-live work parks as WAITING until
 * {@code resumeAt} (wall time) and resumes then (SPEC §5.15).
 */
public final class BudgetExhaustedException extends RuntimeException {

  private final String windowId;
  private final long resumeAt;

  public BudgetExhaustedException(String windowId, long resumeAt) {
    super("budget window " + windowId + " is exhausted until " + java.time.Instant.ofEpochMilli(resumeAt));
    this.windowId = windowId;
    this.resumeAt = resumeAt;
  }

  public String windowId() {
    return windowId;
  }

  public long resumeAt() {
    return resumeAt;
  }
}
