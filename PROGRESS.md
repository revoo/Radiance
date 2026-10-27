# Progress

## Resume here
- Milestone: **M3 — Content library & genesis**, branch `m3-content-genesis` (from `main` at 0615af7,
  which holds M0–M2, CI green). Ends at ⏸ REVIEW.
- Current step: 5 — flesh-out, world-seed export/import (steps 1–4 committed: content module, starter library,
  roster, skeleton generator).
- Half-done: nothing.
- M2's live-provider ACs need R's keys: put them in `.env` and run `./gradlew :backend:liveTest`.
- CI runs: `https://api.github.com/repos/revoo/Radiance/actions/runs` (public repo).

## M3 steps
1. `content` module: def schemas, YAML loader, cross-validation, blacklist, theme linter (ADR 0015) ✔
2. Starter library: 26 quirks, 58 events, 16 arcs, 16 constraint tags, rules files ✔
3. `roster.yaml` (30 + you + pinned) with §6.2 validation ✔
4. Skeleton generator (seeded) ✔: traits, politics, biases, quirks, interests, taste, temperament,
   faithfulness, routines, relationship graph, starting arcs, entities
5. Flesh-out on the `genesis` route (5 per call) + relationship backstories; theme-linter rejection;
   `world-seed.json` export/import; `genesis` Gradle task; director endpoints; regenerate one resident
6. R's onboarding and first impressions
7. World bible (Chicagoland section for review), PROGRESS, ⏸ REVIEW handoff

## Done
- **M2 — LLM gateway, providers & cost meter** (2026-10-27), branch `m2-llm-gateway`:
  - `LlmGateway` by purpose; `RouteTable` from `radiance.llm` (§9) with startup checks; FAKE mode for
    `offline`/`test`; keyless models skipped in LIVE mode, empty routes served by FakeLlm with a warning.
  - Output records → JSON Schema; parser strips fences/prose, validates with paths, normalizes enum
    casing; one repair call; fallbacks; transient retries; truncation retry; 5×/5-min circuit breaker;
    priority-ordered per-provider concurrency; batch warm-up (`callAll`); prompt notes per alias.
  - Adapters: `AnthropicLlm` (Messages, cache_control, output_config, effort/thinking from params,
    never sampling params) and `OpenAiCompatibleLlm` (json_schema/json_object/prompt, Workers AI
    envelope); `FakeLlm`; record/replay (`Recordings`).
  - Cost from alias prices; `llm_call` rows; Micrometer `radiance.llm.*`; budget windows
    (daily/monthly/per-batch USD, provider quotas, 429 closures) in `budget_window`;
    `BudgetExhaustedException` parks non-live work; degradation level 0–6.
  - Tests: 99 in `check` (fakes, MockRestServiceServer); `liveTest` runs only by hand.
  - AC: offline runs with no keys ✔; moving a purpose between providers is config only ✔ (unit test;
    the live half needs keys); a route lacking a needed capability fails startup ✔; JSON-only output is
    validated and repaired ✔; a simulated outage falls over ✔; cache reads recorded wherever reported
    ✔ (adapter + gateway tests; live check needs keys); haiku returns no thinking blocks — **live test
    written, not yet run (no keys)**; a budget breach parks work until its window resets (fake clock) ✔;
    a quota error closes the provider's window until its reset ✔.
  - ADR 0014.
- **M1 — Persistence & schema** (2026-10-27), branch `m1-persistence`:
  - Kernel: deterministic `Ulid` (time + seeded stream), `WallClock`/`ManualClock`, in-house
    xoshiro256** `RandomSource` (reference vector + golden values pinned), `Json` (Jackson 3).
  - `Sql` port, `SqlAdmin` (unfenced bootstrap/lease path), `UnitOfWork` (D1 limits, chunked
    inserts/upserts, arg normalization), `JdbcSql` (WAL, pooled reads, one writer connection).
  - Migration runner (checksummed, atomic per file) and `0001_init.sql` with the agreed fixes.
  - Writer lane; fenced writer lease with `LeaseKeeper` (acquire/renew/release); placeholder world row.
  - Repositories: world, cast, relationships, arcs, canon (entities/facts/knowledge), events,
    social (posts/comments/reactions), scheduled intents.
  - Tests (45, all green): contract suite (`SqlContractTests`, reusable for D1), schema rules, migration
    runner, UnitOfWork properties, repository round trips, Spring wiring, Modulith verify.
  - AC: contract tests pass ✔; 1,000-row unit commits atomically with every statement ≤ 100 params ✔;
    migrations idempotent ✔; off-lane write fails fast ✔; a unit from a process that lost the lease
    aborts ✔. Also verified by hand: offline `bootRun` creates the DB and takes the lease (epoch 1).
  - ADRs 0010 (wall vs world time), 0011 (IDs and cursors), 0012 (persistence implementation),
    0013 (schema deviations, index policy, row records).
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
- Schema: `ux_job_active` covers WAITING; `render_status` CHECKs on comment/dm_message; two extra
  read-path indexes (ADR 0013).
- Writes outside the writer lane exist for bootstrap and the lease only, via `SqlAdmin` (ADR 0012).
- Shared vocabulary enums (`AirStatus`, `RenderStatus`, `Visibility`, `Fidelity`, `ContentOrigin`) live
  in `kernel`, beyond §3.5's list for it, to keep modules acyclic (ADR 0013).

- `LlmRequest` has an extra `priority` component; `Usage.input` means uncached input (ADR 0014).

## Open questions for R
- Provider keys: which providers will you use? Put their keys in `.env` and run
  `./gradlew :backend:liveTest` (well under a cent at default models) to close M2's live ACs, including
  two unverified Anthropic details (Appendix C note).
