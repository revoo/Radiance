package app.radiance.llm;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Where every attempt is recorded ({@code llm_call}, SPEC §5.15). */
public interface LlmCallLog {

  /** One attempt, as it lands in {@code llm_call}. Times are wall time (ADR 0010). */
  record Entry(
      String id,
      Purpose purpose,
      String provider,
      String alias,
      String model,
      int attempt,
      Outcome outcome,
      int inputTokens,
      int cacheReadTokens,
      int cacheWriteTokens,
      int outputTokens,
      int reasoningTokens,
      double costUsd,
      long latencyMs,
      String error,
      String promptRef,
      long createdAt) {}

  void record(Entry entry);

  /** Keeps entries in memory; for tests and the harness. */
  final class InMemory implements LlmCallLog {
    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void record(Entry entry) {
      entries.add(entry);
    }

    public List<Entry> entries() {
      return List.copyOf(entries);
    }
  }
}
