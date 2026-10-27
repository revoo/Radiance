package app.radiance.llm;

/**
 * How big a prompt may be for a purpose (SPEC §5.10): the smallest {@code max-context} on its route,
 * capped by {@code max-prompt-tokens}, plus a characters-per-token estimate calibrated from usage.
 */
public record PromptBudget(int maxPromptTokens, double charsPerToken) {

  public int estimateTokens(String text) {
    return estimateTokens(text.length());
  }

  public int estimateTokens(int chars) {
    return (int) Math.ceil(chars / charsPerToken);
  }

  public int maxChars() {
    return (int) (maxPromptTokens * charsPerToken);
  }
}
