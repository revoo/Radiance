package app.radiance.llm;

import app.radiance.kernel.Json;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes {@code llm_call} rows through the writer lane, without making the caller wait. */
public final class SqlLlmCallLog implements LlmCallLog {

  private static final Logger log = LoggerFactory.getLogger(SqlLlmCallLog.class);

  private static final List<String> COLS = List.of("id", "purpose", "provider", "alias", "model",
      "attempt", "outcome", "input_tokens", "cache_read_tokens", "cache_write_tokens",
      "output_tokens", "reasoning_tokens", "cost_usd", "latency_ms", "error", "prompt_ref",
      "created_at");

  private final Sql sql;
  private final WriterLane lane;

  public SqlLlmCallLog(Sql sql, WriterLane lane) {
    this.sql = sql;
    this.lane = lane;
  }

  @Override
  public void record(Entry e) {
    var row = Arrays.<Object>asList(e.id(), e.purpose().key(), e.provider(), e.alias(), e.model(),
        e.attempt(), e.outcome(), e.inputTokens(), e.cacheReadTokens(), e.cacheWriteTokens(),
        e.outputTokens(), e.reasoningTokens(), e.costUsd(), e.latencyMs(), truncate(e.error()),
        e.promptRef(), e.createdAt());
    var uow = new UnitOfWork().insertRows("llm_call", COLS, List.of(row));
    lane.submit(() -> sql.commit(uow)).whenComplete((v, ex) -> {
      if (ex != null) log.warn("Could not record llm_call {}: {} ({})", e.id(), ex.toString(),
          Json.write(e.outcome()));
    });
  }

  private static String truncate(String s) {
    return s == null || s.length() <= 2000 ? s : s.substring(0, 2000);
  }
}
