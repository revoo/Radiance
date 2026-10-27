package app.radiance.llm;

/** Provider results, normalized by the adapters (SPEC §5.10). */
public enum Outcome {
  OK,
  REFUSED,
  TRUNCATED,
  INVALID_OUTPUT,
  RATE_LIMITED,
  ERROR,
  TIMEOUT;

  /** Worth retrying on the same model after a backoff. */
  public boolean isTransient() {
    return this == ERROR || this == TIMEOUT;
  }
}
