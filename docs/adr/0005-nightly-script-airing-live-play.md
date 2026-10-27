# 0005 — The nightly script, airing and live play: one planner, two horizons

Status: accepted · 2026-10-27 · SPEC §2 P3, §5.1

## Context
R should never wait: the day must be ready whenever he logs in, and residents must answer him in
seconds. Simulating and rendering on demand at login would be slow and expensive.

## Decision
- The same pure planner runs in two places. The nightly batch writes the next day's script (about a
  day of resident activity, planned and rendered in advance); live play scripts R's interactions a few
  seconds ahead.
- Both produce future-dated items with `air_status = SCRIPTED`. `WorldEngine.advanceTo(t)` airs them
  when their time comes — re-validating each against the committed world, applying effects, or
  dropping it and its dependents — and is the only way committed time moves.
- Nothing scripted is visible to R or canon for live prose until it airs.
- The Pacer decides when live items aimed at R become visible; the engagement model decides who.

## Consequences
- Login costs nothing; airing a whole day is milliseconds and makes no LLM calls.
- The script is speculative, so airing needs validation and drop cascades (optimistic concurrency
  for a story).
- Missed nights need backfill; idle decay tapers fidelity and cost while R is away.
