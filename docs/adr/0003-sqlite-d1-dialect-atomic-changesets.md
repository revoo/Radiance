# 0003 — SQLite in D1's dialect, atomic changesets

Status: accepted · 2026-10-27 · SPEC §3.1, §4.2–4.4, P8

## Context
Development is local-first, but M9 moves the database to Cloudflare D1, which is SQLite underneath
with extra limits: no `BEGIN`/`COMMIT`, ≤ 100 bound parameters per statement, statements ≤ 100 KB,
billed row scans and index writes, and atomicity only through `env.DB.batch()`.

## Decision
- SQL lives behind a small `Sql` port (`query`, `queryOne`, `commit(UnitOfWork)`), implemented by
  `JdbcSql` (sqlite-jdbc via `JdbcClient`, WAL, `busy_timeout=5000`, `foreign_keys=ON`) and later
  `D1Sql` (one unit → one edge-Worker `batch()`).
- No JPA/Hibernate. Statements are written by hand in a dialect both run unchanged.
- `UnitOfWork.insertRows` chunks multi-row inserts to ≤ 100 parameters.
- Every write is safe to retry: deterministic IDs, `ON CONFLICT DO NOTHING`, absolute values.
- Migrations are numbered SQL files applied by an in-house runner through the same port.
- One persistence contract suite runs against both adapters.

## Consequences
- Portability is proven by tests, not assumed.
- No ORM conveniences; repositories are row mappers plus statement builders.
- If D1 counts batch statements against its per-invocation query limit, large units need the redo
  log described in §4.2 (decided in M9).
