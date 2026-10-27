# Progress

## Resume here
- Milestone: **M1 — Persistence & schema**, on branch `m1-persistence` (branched from `m0-scaffold`).
- Current step: 1 of 7 — kernel (Ulid, WallClock, RandomSource, Json).
- Half-done: nothing.
- M0 is complete locally; `main` is fast-forwarded only after R confirms CI is green on GitHub
  (the repo is private and `gh` isn't installed, so CI status can't be read from here).
- Next: `./gradlew check`, then continue the M1 step list below.

## M1 steps
1. Kernel: deterministic `Ulid`, `WallClock` (+ `ManualClock`), `RandomSource` (in-house xoshiro256**), `Json`
2. `Sql` port, `Row`/`RowMapper`, `UnitOfWork` (≤100-param chunking, upserts), `JdbcSql`
3. Migration runner + `0001_init.sql` (with agreed fixes)
4. Writer lane (off-lane commits fail fast)
5. Writer lease + fencing, lease keeper, placeholder world row bootstrap
6. Core repositories + persistence contract suite
7. ADRs, `./gradlew check`, PROGRESS, merge

## Done
- **M0 — Scaffold** (2026-10-27), branch `m0-scaffold`:
  - Gradle 9.8.2 wrapper (checksums verified), Boot 4.1.2, Java 25, module packages per §3.5,
    Modulith `verify()`, open `/actuator/health`, offline dev login (ADR 0009).
  - Frontend: Vite 8 + React 19 + TS 6.0 + Tailwind 4 + TanStack Query + React Router + Vitest; dev proxy
    for `/api` and `/actuator`.
  - `.env.example`, ADRs 0001–0009, GitHub Actions CI (backend + frontend jobs).
  - Verified by hand: `bootRun` with the offline profile and no env vars → health UP in 1.3 s with the
    dev-login warning; the frontend at :5173 shows "Backend: UP" through the proxy.
  - Not verified from here: CI green on GitHub (private repo).

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
- TypeScript pinned to 6.0.x, not latest (7.x), for typescript-eslint compatibility (ADR 0009).
- Appendix C updated: Boot 4.1.2 is the latest 4.1 patch.

## Open questions for R
- Please confirm the CI run for `m0-scaffold` is green in GitHub Actions (or install `gh` and run
  `gh auth login` so I can check runs myself).
