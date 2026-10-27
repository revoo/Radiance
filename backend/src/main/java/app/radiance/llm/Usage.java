package app.radiance.llm;

import java.math.BigDecimal;

/**
 * Normalized token usage (ADR 0014). {@code input} is uncached input only; {@code cachedInput} and
 * {@code cacheWrite} are billed at their own prices; {@code output} includes any reasoning tokens,
 * which {@code reasoning} breaks out where a provider reports them.
 */
public record Usage(
    int input, int cachedInput, int cacheWrite, int output, int reasoning, BigDecimal costUsd) {

  public static final Usage ZERO = new Usage(0, 0, 0, 0, 0, BigDecimal.ZERO);

  public Usage plus(Usage o) {
    return new Usage(
        input + o.input,
        cachedInput + o.cachedInput,
        cacheWrite + o.cacheWrite,
        output + o.output,
        reasoning + o.reasoning,
        costUsd.add(o.costUsd));
  }

  public int totalTokens() {
    return input + cachedInput + cacheWrite + output;
  }
}
