package app.radiance.llm;

import java.time.Duration;

/**
 * What came back. {@code value} is set only when {@code outcome} is OK. {@code usage} sums every
 * attempt (retries, repairs, fallbacks); {@code callId} is the final attempt's {@code llm_call} row.
 */
public record LlmResult<T>(
    Outcome outcome, T value, Usage usage, Duration latency, String callId) {

  public boolean ok() {
    return outcome == Outcome.OK;
  }
}
