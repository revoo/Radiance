# Progress

## Resume here
- Milestone: **M0 — Scaffold**, on branch `m0-scaffold`.
- Current step: 1 of 6 — spec, .gitignore, CLAUDE.md, PROGRESS.md, ADR 0001.
- Half-done: nothing.
- Next: generate the Gradle wrapper (Gradle 9.8.2) and the backend skeleton.

## M0 steps
1. Spec, .gitignore, CLAUDE.md, PROGRESS.md, ADR 0001
2. Gradle wrapper + backend skeleton (Boot 4.1.x, Java 25, Modulith verify, health test, offline profile)
3. Frontend (Vite React TS, dev proxy, Vitest)
4. `.env.example`, ADRs 0002–0008
5. GitHub Actions CI, push
6. Verify ACs by hand

## Done
- (nothing yet)

## Decisions agreed with R (2026-10-27, before M0)
Resolutions of spec issues raised at kickoff. Each gets an ADR when its milestone implements it.
1. `offline` profile has a default dev login with a loud startup warning; `local`/`cloud` require
   `RADIANCE_USERNAME` / `RADIANCE_PASSWORD_BCRYPT` (§5.17 vs M0 AC).
2. Two clocks: `WallClock` for operational tables (`job`, `budget_window`, `llm_call`, `batch_run`,
   lease times); `WorldClock` for everything else (§4.1 vs §5.15).
3. IDs: deterministic ULIDs from planned air time + seeded `RandomSource`; API cursors are
   `(created_at, id)`, not bare ULIDs (§4.1, §7).
4. Indexes: index API read paths, not every FK; the engine reads from memory (§4.1 vs §4.4).
5. `ux_job_active` also covers `WAITING` jobs.
6. Jessica's secret romance is seeded with knowers = the couple only; Megan gets a "suspects" tag from
   noticing the beats, so her 👀 is a guess, not a leak (A.1 vs §6.3 vs §1).
7. Genesis history follows the general backfill rule (last 48 h FULL, then REDUCED/SUMMARY) (§6.4 vs §5.1).
8. Unfenced bootstrap path before the writer lane starts: migrations, then a placeholder `world` row
   from config (`ON CONFLICT DO NOTHING`) (§4.2).
9. `render_status` gets CHECK constraints on `comment` and `dm_message`; "mark DROPPED" in §5.10 means
   `render_status = DROPPED`.
10. `content-kind-weights` keys map to post kinds in M4 (STATUS/LIFE_EVENT/CHECKIN → TEXT).
11. `offline` means no network: fake weather, fixture headlines.
12. Real-world ties outside a shared circle (Kristen–Deepa neighbors, Eleni–Sam realtor) are seeded
    as genesis edges despite the "Online" circle's definition.

## Deviations from spec
- (none yet)

## Open questions for R
- (none)
