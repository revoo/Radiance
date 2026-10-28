# Progress

## Resume here
- Milestone: **M3 — Content library & genesis**, branch `m3-content-genesis` (from `main` at 0615af7).
  **All steps done; stopped at ⏸ REVIEW** waiting for R. Don't start M4, and don't merge M3 into `main`
  until R signs off.
- The seed-7 prose is authored in-session (ADR 0017): `backend/src/main/resources/content/genesis/seed-7/`
  holds `residents-1.json` … `residents-6.json` (30 residents) and `ties-1.json` … `ties-3.json` (all 102
  pairs). `AuthoredSeedTests` checks schema, entity ownership, the theme linter, word counts, full
  coverage and that the secret couple reads as friends.
- To rebuild the world bible: delete `backend/data/world-seed.*`, then
  `./gradlew :backend:genesis -Pprofile=offline --args="--seed 7 --authored"` → `backend/data/world-seed.md`.
- After review: apply R's notes (edit the authored files, re-run the line above and `AuthoredSeedTests`),
  then fast-forward `main` once CI is green.
- Half-done: nothing.

## M3 steps (all ✔)
1. `content` module: def schemas, YAML loader, cross-validation, blacklist, theme linter (ADR 0015)
2. Starter library: 26 quirks, 58 events, 16 arcs, 16 constraint tags, rules files, curated tables
3. `roster.yaml` (30 + you + pinned) with §6.2 validation
4. Skeleton generator (seeded)
5. Flesh-out (genesis route) + relationship backstories; `world-seed.json`/`.md`; import; `genesis`
   task; director endpoints; regenerate one resident (ADR 0016)
6. R's onboarding and first impressions (`PUT /api/profile`)
7. World bible with the Chicagoland section

## Done
- **M3 — Content library & genesis** (2026-10-27), branch `m3-content-genesis` — at ⏸ REVIEW:
  - AC: `genesis --seed 7` is deterministic at the skeleton level ✔ (byte-identical JSON; 15 random seeds
    property-tested); import yields exactly the roster's 30 residents with identities unchanged, 206
    relationship rows (150–300 ✔) and 8 active arcs including the pinned seeds ✔; invalid defs, an
    invalid roster or a pinned seed naming a blacklisted arc fail startup with a clear message ✔; genesis
    output never mentions a blacklisted theme ✔ (regenerated once, then rejected; import re-checks).
  - Verified end to end offline: `./gradlew genesis --args='--seed 7 --import'` → 30 residents, 206–208
    ties, 8 arcs, 8 entities, 40 family facts.
  - Prose for seed 7 authored in-session (decisions 13–15, ADR 0017): 30 residents with appearance
    lines and 102 relationship stories; `--authored` builds the seed from them with provenance recorded.
  - Tests: 124 in `check`.
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

## Decisions from R (2026-10-27, during M3; relayed by the planning session)
13. **Build-time content is authored in-session by Opus** (ADR 0017): the content library, the world
    bible and prompt templates, model prompt-notes, bake-off fixture packets, and the genesis
    flesh-out (bios, backstories, voice cards, values/fears/desires, entity looks, relationship
    backstories, per-resident appearance). It goes through the same validation as model output and
    records its provenance. The API flesh-out path and per-resident regeneration stay built and tested
    on FakeLlm; the genesis route config is unchanged. Runtime work (nightly renders, live replies,
    appraisals, headline classification) still needs a model API; keys become necessary at M5 and for
    M2's live checks.
14. **Images by purpose (M12, open until then).** Image fidelity matters, especially for people; some
    spend is fine if it's worth it.
    - A PREMIUM provider for avatars (generated once at genesis, reused everywhere) and arc-milestone
      photos (soft launch, new car, finished kitchen, engagement…); FREE Workers AI FLUX for everyday
      photos (food, sunsets, weather, memes, low-stakes shots).
    - Candidates: Black Forest Labs' top FLUX models (BFL API, fal.ai or Replicate), Google's Gemini
      image models (strong reference-image character consistency), OpenAI's image model. Verify current
      prices and APIs at the time; don't trust memory.
    - Before choosing: an image bake-off of ~6 fixed prompts (2 avatars, a car in a driveway, a group
      from behind at a bar, a snowy Berwyn street, a kitchen mid-renovation) on 2–3 premium candidates
      plus the free tier, side by side with cost per image; R picks by eye.
    - An ADR before adding the new external service (§0); images get their own budget window so image
      spend can't eat the LLM budget; §5.13 rules hold (no identifiable faces except avatars, no
      children, no real people, no logos or text).
    - M3 adds a canonical per-resident appearance line (age, build, hair, style, typical setting); the
      avatar prompt is built from it, and it's kept for future reference-image consistency (same face
      across posts stays V2).
15. **Budget:** R works in 5-hour usage windows. Finish M3 and stop at its REVIEW; don't start M4.
    Authoring the 30 residents is its own resumable step (batches of 5, one commit each).
16. **Ages (R, 2026-10-27):** most residents 18–40, a few over 40. The age floor drops from 22 to 18
    (P9 and §6.2 updated); validation requires at least two thirds aged 18–40. Re-aged roster: 25 of 30
    are 21–40; over 40 are Donna 67, Gary 69, Lisa 56, Eleni 52 and Marko 47. The youngest is 21 because
    bars, drinking and the brewer/open-mic roles need 21+ in Illinois.

## Deviations from spec
- TypeScript pinned to 6.0.x, not latest (7.x), for typescript-eslint compatibility (ADR 0009).
- Appendix C updated: Boot 4.1.2 is the latest 4.1 patch.
- Schema: `ux_job_active` covers WAITING; `render_status` CHECKs on comment/dm_message; two extra
  read-path indexes (ADR 0013).
- Writes outside the writer lane exist for bootstrap and the lease only, via `SqlAdmin` (ADR 0012).
- New `content` module (not in §3.5) for defs, rules, roster, tables and prompts (ADR 0015).
- Genesis backfill + first script deferred to M4/M6; avatars to M12; onboarding UI to M7 (ADR 0016).
- Flesh-out resumes from file checkpoints until the JobRunner exists (M5) (ADR 0016).
- Shared vocabulary enums (`AirStatus`, `RenderStatus`, `Visibility`, `Fidelity`, `ContentOrigin`) live
  in `kernel`, beyond §3.5's list for it, to keep modules acyclic (ADR 0013).

- `LlmRequest` has an extra `priority` component; `Usage.input` means uncached input (ADR 0014).

## Open questions for R
- M3 REVIEW: see the review list in the M3 hand-off (cast sheet, world bible, quirk spread, prejudice).
- Provider keys: which providers will you use? Put their keys in `.env` and run
  `./gradlew :backend:liveTest` (well under a cent at default models) to close M2's live ACs, including
  two unverified Anthropic details (Appendix C note).
