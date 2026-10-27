# 0016 — Genesis: seed file, naming, resumability and what waits for later milestones

Status: accepted · 2026-10-27 · SPEC §5.5, §5.9, §6.2–§6.4, M3 · decisions 6, 7 and 12 agreed with R

## Context
Genesis has to turn a fixed roster into a living cast, let R read and edit it before the world starts,
and stay deterministic, cheap and resumable. Some §6.4 steps depend on systems that don't exist yet.

## Decision
- **One editable file.** Genesis writes `data/world-seed.json` (people keyed by handle, things by
  readable keys, no database ids) plus a regenerated `world-seed.md` cast sheet for reading. Import
  validates the file against the roster (names, genders, ages, statuses never change; invariant 11;
  no blacklisted arcs or themes), then writes the world in one unit. Ids derive from the seed and each
  key, so re-importing the same file is a no-op; a different seed or a world with posts is refused.
- **Determinism:** every random choice draws from a labeled stream (`traits:<handle>`, `graph`,
  `arcs`, …) derived from the seed. Same roster + content + seed ⇒ the same skeleton, byte for byte.
- **Names are the engine's** (§5.5): spouses, children, an ex-spouse or a late spouse are named from
  curated tables (`tables/names.yaml`, `kid_names.yaml`) by heritage and era, recorded as MAJOR facts,
  and handed to the flesh-out prompt, which must not invent other named people.
- **Pinned seeds:** Sarah and Tim take opposite economic sides by coin flip; Sarah's relationship with
  Ryan "showing cracks" lowers her affinity and partner satisfaction; Jessica's secret partner is one
  of the roster's single men, and her secret phase's knowers are the couple only, with Megan tagged
  `suspects` (decision 6). Real-world ties outside circles come from the roster's `knows` (decision 12).
- **Flesh-out** runs on the `genesis` route, 5 residents per call grouped by circle, then relationship
  backstories per circle. Output that mentions a blacklisted theme is regenerated once. Progress is
  checkpointed to the seed file after every batch, so a stopped run (budget, crash) resumes where it
  left off — file-based for now; it becomes jobs when the JobRunner lands (M5).
- **Run modes:** `./gradlew genesis --args='--seed 7 [--skeleton-only] [--import]'` runs the app
  without a web server (`-Dradiance.mode=genesis`); the Director's console has preview, commit and
  regenerate-one-resident endpoints. Web security loads only for the web app.
- **Onboarding** (§5.9) is a backend service and `PUT /api/profile`: R's profile, one appraisal, first
  impressions bounded by each resident's temperament, attraction only from women, connections drawn
  first from R's circles (family excluded).

## Deferred, with the milestone that brings each
- 14 days of backfilled history and the first script (§6.4 steps 5 and 8) need the engine and the batch:
  **M4/M6**. A fresh import starts with arcs mid-phase and no posts.
- Avatars (§6.4 step 6): **M12** (media). Profiles show initials until then.
- The onboarding UI and profile editing: **M7**.

## Consequences
- R can review structure offline (`--skeleton-only`), and prose once a model with a key is configured.
- The seed file is the contract between genesis and import; its `version` guards format changes.
