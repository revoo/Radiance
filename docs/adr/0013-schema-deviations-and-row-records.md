# 0013 — Schema deviations, index policy and row records

Status: accepted · 2026-10-27 · SPEC §4.1, §4.3, §4.4, §5.10, §5.15 · agreed with R at kickoff (decisions 4, 5, 9)

## Context
`0001_init.sql` follows SPEC §4.3, but kickoff review found gaps: `ux_job_active` ignored WAITING
jobs; `comment` and `dm_message` lacked the `render_status` CHECK that `post` has; and §4.1 ("index
every foreign key") contradicts §4.4 ("keep indexes to the query paths actually used").

## Decision
- **Changes to the listed schema:**
  - `ux_job_active` covers `PENDING`, `RUNNING` and `WAITING`, so a parked job can't be enqueued twice.
  - `render_status` CHECK (`PLANNED|RENDERED|FAILED|DROPPED`) on `comment` and `dm_message`.
    "Mark the item DROPPED" in §5.10 means `render_status = DROPPED`.
  - Two read-path indexes: `ix_friend_request_to(to_id, status)`, `ix_dm_thread_last(last_message_at)`.
- **Index policy:** index the API's read paths and the engine's load paths. Foreign keys that only the
  in-memory engine follows stay unindexed, because on D1 every index costs a row written per write.
- **Row records:** each aggregate has plain records mirroring its rows and a `Table<R>` mapping
  (column names next to getters). JSON columns stay strings until the world model parses them (M4).
  Vocabulary used across modules (`AirStatus`, `RenderStatus`, `Visibility`, `Fidelity`,
  `ContentOrigin`) lives in the kernel to keep modules acyclic.

## Consequences
- The spec's §4.3 listing and the migration differ only as above; SPEC.md stays as written.
- New read paths add their index in a new migration alongside the code that needs it.
