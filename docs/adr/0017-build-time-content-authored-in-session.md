# 0017 — Build-time content is authored in-session

Status: accepted · 2026-10-27 · SPEC P7, §6.4, §5.10 · R's decision (PROGRESS decision 13)

## Context
Genesis was designed to flesh out the cast on the `genesis` route (Sonnet, ~$1–2). R would rather the
coding session's own model (Opus) write everything that's written once at build time, and keep model
APIs for what has to happen at runtime.

## Decision
- Opus, in the coding session, writes the content library, the world bible and prompt templates,
  prompt-notes, bake-off fixture packets, and the genesis flesh-out.
- The flesh-out lives as tracked content (`content/genesis/seed-<n>/*.json`): batches of residents and
  relationship stories in exactly the shape the `genesis` route returns. `genesis --seed 7 --authored`
  feeds them through the same path as a model's answer — schema validation, handle matching, the theme
  linter — then the seed file records the provenance ("authored in-session by Opus").
- Authored prose belongs to one skeleton (seed 7): names, ties and storylines it refers to come from
  that seed. A different seed needs new prose or the API path.
- The API path stays: `genesis --seed N` on the `genesis` route and per-resident regeneration are built
  and tested on FakeLlm, ready for when keys exist. Route config is unchanged.

## Consequences
- No model API is needed until M5 (runtime rendering); genesis costs nothing in API spend.
- Authored prose is reviewable in git diffs, one commit per batch, and resumable between usage windows.
