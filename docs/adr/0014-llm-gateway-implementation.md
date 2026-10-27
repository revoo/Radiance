# 0014 — LLM gateway implementation choices

Status: accepted · 2026-10-27 · SPEC §2 P5, P10, §5.10, §5.15, M2

## Context
SPEC §5.10 and §5.15 fix the gateway's shape, but leave details open: how live calls are told apart,
how usage is normalized across providers, how the degradation ladder maps to budget use, what
"parking" means before the JobRunner exists, and where stored prompts live.

## Decision
- **`LlmRequest.priority`** (`LIVE | SCRIPT | BACKFILL | BACKGROUND`) is added to the spec's record. It
  orders each provider's concurrency queue (live first) and picks the budgets that apply: live calls
  see only the monthly cap and provider quotas; everything else also sees daily and per-batch caps.
- **Normalized usage:** `input` is uncached input only; `cachedInput` and `cacheWrite` are separate;
  `output` includes reasoning tokens (as providers bill them) and `reasoning` breaks them out. Cost is
  computed from the alias's prices, never taken from the provider.
- **Attempt policy:** invalid output → one repair call on the same model (the error and the previous
  reply go at the end of the content, so the cached prefix is untouched), then the next model;
  transient errors → 2 retries with backoff (not for 4xx, which can't succeed on retry), then the next
  model; truncation → one retry at twice the limit; refusal → next model, never a softened brief;
  rate limit → close the provider until its `Retry-After` (60 s default) and move on.
- **Parking:** budgets are checked before every attempt with an upper-bound estimate (whole prompt
  uncached + full output limit). A call that doesn't fit throws `BudgetExhaustedException(window,
  resumeAt)` before anything is sent; the JobRunner (M5) turns it into `WAITING` with
  `run_after = resumeAt`. Live calls that hit provider limits fall down the route instead.
- **Degradation ladder:** level 0 below 80 % of any USD window, then six equal steps to 100 %. The
  gateway implements rung 6 (live routes cheapest-first); consumers read the level for the others.
- **Budget windows** persist to `budget_window` through the writer lane, changed rows only; the
  per-batch window exists only while a batch runs; work parked on it waits for the next batch.
- **Prompt store:** files under `data/prompts/<day>/<callId>.json` (not rows: D1 caps rows at 1 MB);
  `llm_call.prompt_ref` holds the relative path.
- **Record/replay:** responses keyed by a SHA-256 of model id, purpose, prefix, content, schema and
  output limit; `radiance.llm.recordings.mode: RECORD | REPLAY`.
- **Secrets:** `spring.config.import` reads an optional git-ignored `.env` (repo root or working dir).
- **Live contract tests** are `@Tag("live")`, excluded from `check`, run by `./gradlew :backend:liveTest`.

## Consequences
- Domain code states urgency once (priority) and never sees providers.
- The pre-call estimate is conservative: near a cap, a call may park even though it would have fit.
- Two Anthropic details are not verifiable without a key: the placement of `effort` under
  `output_config` and the `thinking` type strings. The live contract tests check both.
