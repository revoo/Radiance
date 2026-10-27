package app.radiance.llm.provider;

import app.radiance.llm.Outcome;

/**
 * An adapter's normalized answer.
 *
 * @param text the concatenated text blocks (thinking blocks excluded); null unless the provider answered
 * @param modelId the model the provider says served the call, or the configured id
 * @param thinkingBlocks how many thinking blocks came back (aliases that disable thinking expect 0)
 * @param retryAfterMs for RATE_LIMITED: how long the provider asked us to wait, if it said
 */
public record ProviderResponse(
    Outcome outcome,
    String text,
    TokenCounts tokens,
    String modelId,
    int thinkingBlocks,
    Long retryAfterMs,
    String error) {

  /** Normalized counts (ADR 0014): {@code input} excludes cached reads and cache writes. */
  public record TokenCounts(int input, int cachedInput, int cacheWrite, int output, int reasoning) {
    public static final TokenCounts ZERO = new TokenCounts(0, 0, 0, 0, 0);
  }

  /** Prefix on errors that are our fault (a 4xx): retrying the same request can't help. */
  public static final String CLIENT_ERROR = "client error: ";

  public boolean isClientError() {
    return error != null && error.startsWith(CLIENT_ERROR);
  }

  public static ProviderResponse ok(String text, TokenCounts tokens, String modelId) {
    return new ProviderResponse(Outcome.OK, text, tokens, modelId, 0, null, null);
  }

  public static ProviderResponse failure(Outcome outcome, String modelId, String error) {
    return new ProviderResponse(outcome, null, TokenCounts.ZERO, modelId, 0, null, error);
  }
}
