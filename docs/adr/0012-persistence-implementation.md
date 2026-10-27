# 0012 — Persistence implementation: writer lane, lease, bootstrap path

Status: accepted · 2026-10-27 · SPEC §3.3, §4.2 · bootstrap agreed with R at kickoff (decision 8)

## Context
SPEC §4.2 says `Sql.commit` is callable only from the writer lane and that every unit is fenced by the
lease. But migrations also run through the port, the lease lives on the `world` row (which doesn't
exist on a fresh database), and acquiring the lease is itself a write.

## Decision
- **Two write paths.** `Sql.commit` (lane-only, fenced) is for world state. `SqlAdmin.execute`
  (unfenced, any thread) is only for migrations, the placeholder world row, and the lease's own
  conditional updates. Domain code never uses it. `D1Sql` will implement both the same way.
- **Startup order:** open the database → migrate → insert the placeholder world row from
  `radiance.world.*` (`ON CONFLICT DO NOTHING`; genesis replaces it) → the `LeaseKeeper` lifecycle
  bean acquires and renews the lease.
- **Lease:** owner id `app@host:pid:random`, TTL 30 s, renewed every 10 s, released on shutdown.
  Acquiring is one conditional UPDATE that takes over only a free or expired lease and bumps the
  epoch; a read-back tells the process whether it won. A crashed writer blocks others for ≤ 30 s.
- **Fence:** each committed unit starts with an upsert into `lease_fence` whose NOT NULL column gets
  NULL unless `world.lease_owner/lease_epoch` still match. A failure becomes `LeaseLostException` and
  clears the process's belief that it holds the lease.
- **JdbcSql:** reads through `JdbcClient` on a 4-connection Hikari pool; writes through `JdbcClient`
  on one dedicated connection in one transaction per unit, guarded by a lock shared with the admin
  path. Dependencies: `spring-jdbc`, `HikariCP`, `sqlite-jdbc` (all Boot-managed, within §3.4).
- **Writer lane:** a single-threaded scheduled executor; the WorldEngine loop runs on it in M4.
- **Migrations:** numbered files, one unit each (statements + its `schema_migrations` row), SHA-256
  checksummed with line endings normalized; editing an applied file fails startup.

## Consequences
- The M1 acceptance criteria are covered by a contract suite that M9 reruns against D1.
- Reloading the in-memory world on lease acquisition is added with the WorldEngine (M4).
