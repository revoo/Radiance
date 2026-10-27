# 0004 — The LLM narrates and never plans

Status: accepted · 2026-10-27 · SPEC §2 P1, P2, P6

## Context
A story engine needs continuity, low cost, speed and testability. Letting a model decide what happens
makes all four hard: outputs are nondeterministic, costly per decision, slow, and can contradict canon.

## Decision
- Who does what, when, to whom and with what stance is decided by deterministic, seeded code: a
  discrete-event simulation with utility-based decisions and a storyteller.
- The LLM only turns the engine's briefs into words. LLM output may feed the planner only as stored,
  structured inputs produced earlier (a headline classification, an appraisal of R's post), never as
  a call inside the planning loop.
- Major facts are created only by the engine. Prose may add minor details, which are extracted and
  recorded as MINOR facts.

## Consequences
- Same seed + same inputs + fake LLM ⇒ identical world; the whole engine is unit- and property-testable
  offline.
- Prose quality depends on brief quality; the engine must say enough (stance, beats, constraints).
- Validators (linter, judge, fact checks) are needed because the renderer can still drift.
