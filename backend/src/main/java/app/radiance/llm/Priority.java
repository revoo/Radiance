package app.radiance.llm;

/**
 * Who is waiting on a call (SPEC §5.10): live renders first, then scripts, backfills and background
 * work. Live calls queue ahead on provider semaphores and are never stopped by the daily or per-batch
 * budget, only by the monthly cap and provider quotas (SPEC §5.15).
 */
public enum Priority {
  LIVE,
  SCRIPT,
  BACKFILL,
  BACKGROUND
}
