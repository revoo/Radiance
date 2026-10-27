# 0006 — No vector database or embeddings

Status: accepted · 2026-10-27 · SPEC §2 P8, §5.7

## Context
Context packets need relevant memories per resident. Embedding-based retrieval is the common answer,
but it adds a service or extension, an embedding model and its cost, and nondeterminism.

## Decision
Retrieve memories structurally: a SQL pre-filter (owner, not compacted, and shared participants, an
active arc, importance ≥ 7 or the last 3 days; limit 200), then a Java score of
`0.5·recency + 0.3·importance + 0.2·relevance` (participant, tag and arc overlap). Top 6 per actor.

## Consequences
- Deterministic, cheap, testable and D1-compatible.
- Retrieval can miss semantically related memories that share no participants or tags.
- Revisit only when retrieval quality measurably suffers (SPEC §13).
