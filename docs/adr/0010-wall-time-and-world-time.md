# 0010 — Wall time and world time

Status: accepted · 2026-10-27 · SPEC §4.1, §5.1, §5.15 · agreed with R at kickoff (decision 2)

## Context
SPEC §4.1 says every timestamp is in world time (wall time + `world.time_offset_ms`). But some state
follows the real calendar whatever the world does: provider quota resets, the Workers AI neuron reset
at 00:00 UTC, cron schedules, and lease expiry between processes. After a 6-hour fast-forward, a
world-time `budget_window.resets_at` would park jobs at the wrong moment.

## Decision
- The kernel's `WallClock` is real time. `world.WorldClock` (M4) derives world time from it plus the
  offset. They are separate types so the two can't be mixed by accident.
- **Wall time:** `world.lease_until`, `session_log.*`, `job.run_after/locked_at/created_at`,
  `budget_window.resets_at/closed_until`, `batch_run.started_at/finished_at`, `llm_call.created_at`,
  `headline.fetched_at`, `weather_hour.fetched_at`, `schema_migrations.applied_at`.
- **World time:** everything else, including every content row's `created_at` (air time),
  `committed_until`, `scripted_until`, arcs, facts, memories and `weather_hour.hour`.
- The header of `0001_init.sql` lists the wall-time columns.

## Consequences
- Fast-forward and pause never confuse budgets, quotas or leases.
- Code that writes an operational row must take a `WallClock`; code that writes content takes the
  world clock. Reviews should check which one a new column uses.
