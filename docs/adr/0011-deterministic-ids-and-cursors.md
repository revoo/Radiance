# 0011 — Deterministic IDs and (created_at, id) cursors

Status: accepted · 2026-10-27 · SPEC §4.1, §4.2, §5.1, §7, P6 · agreed with R at kickoff (decision 3)

## Context
IDs are time-sortable ULIDs, and the API pages with "ULID cursors". But scripted items are minted the
night before they air, and a hook can move an item's air time forward (§5.1), so ID order isn't air
order. The "Top" feed isn't chronological at all. Separately, retries must write the same rows and the
same seed must produce the same plan hash, so IDs can't come from a hidden clock or `SecureRandom`.

## Decision
- `Ulid.of(timeMs, RandomSource)`: the caller supplies the time and a seeded stream. For scripted
  items the time is the planned air time and the stream is the slice's; for R's actions, the live
  session's seeded stream.
- `RandomSource` is an in-house xoshiro256** with SplitMix64 seeding, checked against the reference
  vector and pinned by golden-value tests, so worlds don't change across JDK upgrades. Child streams
  are derived by hashing labels, not forked, so they don't depend on the parent's draw count.
- Chronological API cursors are `(created_at, id)`. "Top" pages by its own score cursor (M7).

## Consequences
- Same seed + same inputs ⇒ same IDs ⇒ same plan hash; retried units are idempotent.
- An ID's timestamp is a hint (when the item was planned to air), not the truth; `created_at` is.
