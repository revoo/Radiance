# 0001 — Record architecture decisions

Status: accepted · 2026-10-27

## Context
SPEC.md is the source of truth, but it leaves some choices open ("Your call") and is silent on others.
Decisions made during the build need to survive context resets and be explainable later.

## Decision
Record each significant decision as a one-page ADR in `docs/adr/NNNN-title.md`: context, decision,
consequences. Number them sequentially and never renumber. A superseded ADR stays, marked
"superseded by NNNN". ADRs 0002–0008 record decisions the spec already makes; later ones record
choices made while building, including resolutions of spec contradictions agreed with R.

## Consequences
- Every new dependency, process or external service needs an ADR first (SPEC §0).
- Deviations from the spec are listed in PROGRESS.md and point at their ADR.
