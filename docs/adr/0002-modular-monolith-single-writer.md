# 0002 — Modular monolith, single writer, fenced lease

Status: accepted · 2026-10-27 · SPEC §2 P4, P8, §3.3, §3.5, §4.2

## Context
One human user, thirty simulated residents, a nightly batch and live play. World-state changes are
frequent and interdependent (an aired comment moves a meter, writes a memory, maybe a notification).
The target databases, SQLite and D1, are single-writer by nature, and D1 has no interactive transactions.

## Decision
- One Spring Boot process, packaged by feature and verified by Spring Modulith. No microservices,
  no broker; background work uses a DB-backed job table.
- All world-state mutations run on one dedicated writer thread (the writer lane) against an in-memory
  world model, and persist as atomic `UnitOfWork` changesets. API reads go to the database.
- A writer lease on the `world` row (`lease_owner`, `lease_until`, `lease_epoch`) guarantees only one
  process writes at a time. Every unit starts with a fencing statement that fails unless the process
  still holds the current epoch, so a stale writer's unit aborts atomically.

## Consequences
- No locks, no lost updates, no optimistic-retry logic in domain code.
- Writes are serialized, so each unit must stay small (milliseconds) to keep live play responsive;
  the batch submits one slice per lane task.
- A one-shot batch process and the app can't both write; the loser exits DEFERRED (§5.1).
