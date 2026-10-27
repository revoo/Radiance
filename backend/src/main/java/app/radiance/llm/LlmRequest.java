package app.radiance.llm;

import java.time.Duration;
import java.util.List;

/**
 * A call in the domain's terms (SPEC §5.10): a purpose, prompt blocks and the record type to get back.
 * {@code priority} is an addition to the spec's record (ADR 0014): it orders provider queues and decides
 * which budgets apply.
 */
public record LlmRequest<T>(
    Purpose purpose,
    List<Block> prefix,
    List<Part> content,
    Class<T> output,
    int maxOutputTokens,
    Duration timeout,
    Priority priority) {

  public LlmRequest {
    prefix = List.copyOf(prefix);
    content = List.copyOf(content);
    if (!output.isRecord()) throw new IllegalArgumentException("output must be a record: " + output);
    if (maxOutputTokens <= 0) throw new IllegalArgumentException("maxOutputTokens must be positive");
  }

  /** Total characters of prompt text, for token estimates. */
  public int promptChars() {
    int n = 0;
    for (var b : prefix) n += b.text().length();
    for (var p : content) if (p instanceof Part.Text t) n += t.text().length();
    return n;
  }

  public LlmRequest<T> withMaxOutputTokens(int max) {
    return new LlmRequest<>(purpose, prefix, content, output, max, timeout, priority);
  }
}
