# Progress

## Resume here
- Milestone: **M7 — Feed UI & live play**, branch `m7-live-play`. **All steps done; ⏸ REVIEW**: R plays for
  20 minutes and tunes `content/rules/pacing.yaml`. Don't merge into `main` or start M8 until R says so.
- To play offline: `./gradlew genesis --args='--seed 7 --import' -Pprofile=offline` (once), then
  `./gradlew bootRun --args='--spring.profiles.active=offline'` and `cd frontend && npm run dev`; open
  http://localhost:5173 and log in with the built-in offline dev login. The pacing monitor is under
  "Pacing monitor" in the left rail (`/director/pacer`).
- Half-done: nothing.
- Observations for R's review:
  - Right after onboarding, a post of R's draws about 3 reactions and fewer than one comment inside the
    5-minute burst (browser check: 3 reactions and 1 comment). The harness medians (11 / 3) also count
    residents finding the post in later scripts. Who responds is the M4-tuned engagement model, not
    the Pacer; `user-salience` and `theta_comment` are the knobs if live bursts should be bigger.
  - Offline prose is FakeLlm's: short, generic lines. Real voices need provider keys (open question).

## M7 steps (all ✔)
1. Live core: the `Pacer` (pure: response curves, token buckets with a hard per-minute cap, priority
   release, breathing room, hooks, seeded timing); `WorldEngine` locking and aired ids; `UserActions`
   (R's writes; reflex and considered responders); `Appraiser` (the `appraisal` route, cached by content
   hash, offline heuristics)
2. `LivePlay`: sessions and heartbeats, the real-time loop (airing, Pacer releases, typing indicators,
   hooks), live renders on virtual threads, SSE events, background backfill when no script covers now
3. REST + security: JSON login with CSRF cookie and rate limiting; me, heartbeat, world, recap, profile,
   feed, posts, comments, reactions, people, notifications, stream; `GET /api/director/pacer`
4. The SPA: shell, login, onboarding, feed (Recent/Top), PostCard, comments, ReactionPicker, composer
   with @mentions, profile, friends, notifications, recap card, new-posts pill, typing rows, toasts,
   backfill banner, live world clock, light/dark themes, `useRadianceStream`, heartbeats, pacing monitor
5. AC tests (`PacerTests`, `LivePlayTests`, `ApiTests`, Vitest) and an end-to-end check in the browser

## M6 steps (all ✔)
1. Spring wiring: `SimConfig` (`Knobs` from `radiance.*`, `WorldEngine`, `ScriptWriter`); the config sections
2. `NightlyBatch` (§5.1) with `batch_run` reports and the script's plan hash; `BatchScheduler`
3. One-shot `./gradlew batch` (`BatchCommand`), DEFERRED when the app holds the lease
4. Genesis imports 14 days back and publishes `GenesisImported`; `FirstBatch` backfills and writes the
   first script. OS-timer recipe: `docs/ops/nightly-batch.md`
5. AC tests: `NightlyBatchTests`, `FirstBatchTests`

## M5 steps (all ✔)
1. Jobs: `JobRepository`, `JobRunner` (priorities, virtual threads, parking at a budget window, retries,
   crash recovery; claims only kinds it handles)
2. Narrative core: prompt templates, `ContextPacketBuilder` (stable prefix; Today / people / canon /
   thread / briefs), `MemoryRetrieval`, `RenderBatch`, offline `FakeRender`
3. `Renderer` (RENDER_THREAD / RENDER_DM) with `ProseChecks`: ids, length bands, constraint linter +
   judge, must_not_reveal, blacklist, major facts, LLM-isms, softening, hard lines; re-render once, then
   a reaction or DROPPED; refusals → reactions; facts PENDING → ACTIVE / VOID at airing; gists into
   memories; recap lines
4. Script → jobs (in air-time order, committed with each slice); fidelity caps; `ProjectedMeter`;
   `RenderedRun`
5. `Maintenance`: REFLECT compaction and SUMMARY refreshes
6. `Bakeoff` + `llm-bakeoff` run mode
7. AC tests: `ConstraintFixtureTests` (7 days, adversarial renderer), `RenderedRunTests`,
   `ContextPacketTests`, `RendererTests`, `MaintenanceTests`, `BakeoffTests`

## M4 steps (all ✔)
1. Foundations: migration `0002_sim.sql`; repositories for memory, notification, DM, weather, headlines,
   actor constraints; `FakeWeather` + named conditions; fixture headlines + selection; the calendar
2. The world model (`sim.model`), `ModelStore` (load / dirty-row write), exact OU drift, temperament
   meter math and lazy relaxation, `Brief`
3. Airing: `WorldEngine.advanceTo`, validation (def conditions, weather, arc phase, parents, attention),
   drop cascades, effects (one path, shared with planning)
4. Planning: presence, rates + IPF event-mix calibration, storyteller, selectors, the arc engine, news
   takes, engagement, stances, reactions, threads/intents, fidelity + backfill, `ScriptWriter` + plan
   hash, `ContinuityAuditor`
5. Harness: `Simulation`, `Persona`, `LiveResponder`, `Impressions` (hysteresis), `FeelReport`,
   `ScriptDump`, `./gradlew simulate`; `FeelBandsTests`, `SimulationPropertyTests`

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
- **M7 — Feed UI & live play** (2026-10-29), branch `m7-live-play`:
  - AC: logging in shows a ready feed straight from the database (API test: first feed request well
    under 1 s, no catch-up step) ✔; after R posts, the first reaction comes within 2–6 s and the first
    comment within 8–25 s, tapering within about 5 minutes (`PacerTests` over 50 seeds, `LivePlayTests`
    end to end; browser: 5.2 s and 15.6 s) ✔; never more than 6 comments or 4 notifications reach R in
    any minute (hard per-minute caps on top of the token buckets) ✔; a late render shows "… is writing a
    comment" until it lands, and a failed one just goes away ✔; with no script, the first request
    starts a background backfill and the feed still loads ✔.
  - 201 backend tests, 43 frontend tests; lint and build clean.
  - Fixes from R's first play (2026-10-29), each with a regression test that fails without it: logging
    out returned to the feed (the client never re-asked who R is; logout now lives in an account menu
    that also works on phones); comment and reply reaction badges covered short text (they now hang off
    the bubble's edge). Added real-path tests: the SPA's login/CSRF/logout flow on a real server
    (`SpaSessionTests`), the real loop thread on the wall clock, unreleased responses airing later.
- **M6 — Nightly batch (local)** (2026-10-28), branch `m6-nightly-batch`:
  - AC: a FULL night writes and renders in seconds offline and projects under $0.15 at haiku prices ✔;
    a second run the same night plans, renders and calls nothing ✔; a run killed mid-render is
    completed by the next with the same plan hash ✔; a tiny daily budget leaves the night PARTIAL with
    renders parked, and after the reset they finish with nothing lost or rendered twice ✔; a one-shot
    run with the lease held elsewhere records DEFERRED, and the lease holder's scheduler runs it ✔; two
    skipped nights are backfilled by the next run ✔; every event of importance ≥ 6 has a recap line ✔.
  - Verified end to end offline: `genesis --import` → first batch backfilled 336 h in 78 slices and
    wrote the first script; `./gradlew batch` the same night → DONE, nothing to do.
  - Tests: 180 in `check`.
- **M5 — Narrative rendering** (2026-10-28), branch `m5-narrative` — accepted by R on the offline results and merged:
  - AC, offline: zero unhandled constraint violations and zero blacklisted-theme mentions across 7
    scripted days of the constraint-heavy fixture, against a renderer that writes every violation on
    its first try ✔; a FULL day projects under $0.15 at haiku prices with one cached prefix and ≥ 80% of
    calls hitting it ✔ (`ProjectedMeter`, estimated tokens). The real meter and the provider-reported
    cache-hit rate need a key (open question).
  - Tests: 173 in `check`.
- **M4 — Simulation engine & the script (offline)** (2026-10-28), branch `m4-simulation-engine` — approved by R and merged:
  - AC (amended by decision 17): property tests green ✔ (random seeds × 30 days, auditor throwing,
    no spoilers checked after every advance, blacklist, attention, caps, invariant 11); feel-report
    bands met at default knobs on seed 42 in CI ✔ (seed 7: all but one borderline, see open questions);
    same seed ⇒ same plan hashes ✔. Timing targets dropped as gates (decision 17); a 30-day run takes
    ~15 s.
  - Bands that need the Pacer (first reaction ≤ 6 s, first comment ≤ 25 s, per-minute caps, the quiet
    gap) are M7's and aren't measured yet.
  - Tests: 158 in `check`.
  - Fixed after merge (2026-10-28): CI caught a flaky property run. Cause: `Map.copyOf`/`Set.copyOf`/
    `Map.of` iterate in a per-JVM order, so plans differed between processes (and a secret beat could
    surface through a non-knower's mention). Content and sim collections are now ordered; a golden
    plan-hash test guards it; a 30-seed sweep and the feel bands pass; the faithfulness ladder is steeper.
- **M3 — Content library & genesis** (2026-10-27), branch `m3-content-genesis` — approved by R and merged:
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
17. **No performance gates in V1 (R, 2026-10-27, before M4; relayed by the planning session).** "Most of
    what we are doing is just fancy CRUD and the simulations happen in the batch." **M4's acceptance
    criteria are amended:** the timing targets (a FULL day plans in < 5 s, a 30-day backfill in < 10 s,
    the 30-day `simulate` harness in < 60 s) are dropped as gates, with no optimization work for them;
    if something is noticeably slow, fix it then. Kept: the property tests (no spoilers, blacklist),
    the feel-report bands, and determinism (same seed ⇒ same plan hash), since those make re-running
    and resuming the batch safe. The M7/M8 latency targets (first reaction ~6 s, first comment ~25 s,
    DM typing within 3 s) stay, because they're about how the app feels.
18. **No live bake-off in V1 (R, 2026-10-28).** M5 is accepted on its offline results (the adversarial
    constraint fixture, the projected meter, one cached prefix). Haiku is the default model for every
    purpose, including `recap` and `genesis` (were Sonnet with a Haiku fallback); the `sonnet` alias
    stays defined. R runs `./gradlew llm-bakeoff` and tunes per-purpose models in V2. The live checks of
    M5's meter (≤ $0.15 per FULL day) and provider-reported cache hits wait until keys are in `.env`.

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
- M4: the planner (storyteller, arc engine, engagement, auditor) lives in `sim` beside the world model,
  not in `story`, to keep modules acyclic; `story` keeps canon persistence.
- M4: migration `0002_sim.sql` adds `actor_constraint` (event-made constraint tags; arc-phase tags are
  derived), `relationship.pending_tags_json` (impression hysteresis and R-perception averages),
  `actor_state.interactions_day`, and indexes for the engine's reload paths.
- M4: event modifier semantics (SPEC names the keys, not the math): trait `m` → ×m^((trait−50)/50);
  state `m` → ×m^value; interest `m` → ×(1 + (m−1)·weight); `quirk.*`, `weekend`, `has_kids`,
  `has_partner`, `observance.*`, `season.*`, `temperament.*` → ×m when true (`Rates`).
- M4: guard rails beyond the spec: at most 6 posts per resident per world-day (the storyteller stops at
  5, leaving room for an arc); an ordinary thread stops drawing new commenters at ~37% of the cast
  (drama threads can pile on); a quiet resident's arc beats surface as a friend's mention; the
  storyteller forces one public storyline beat each evening if nothing visible moved that day.
- M4: event-mix calibration counts arc and calendar posts as fixed, caps each def by its cooldown, and
  divides LIFE/DRAMA by the tension factor's expected weekly mean, so the week lands on the mix.
- M4: genesis changes (seed-7 pairs and authored prose unchanged; `AuthoredSeedTests` green): grouches
  start at their full temperament baseline (was half); the 4 quietest residents outside seeded
  storylines post at most 0.1/day, so lurkers exist. Seed-7 effect: Jake's, Amra's and Kendra's starting
  meters are lower; Ricardo, Daniel, Sam, Deepa (and Eleni) are the lurkers.
- M4: content added: four repeatable LIFE defs (`career_win`, `trip_booked`, `home_milestone`,
  `year_reflection`); `kid_milestone` base rate lowered so non-parents rarely post one;
  `engagement.yaml` weights and thresholds tuned to the bands.
- M4: the harness R answers some top-level comments on his posts; the Pacer, R's DMs and friend
  requests come in M7/M8. Render jobs are M5 (scripted rows stay `PLANNED`).

- M5: threads over 25 items render in one call (the split by time window isn't built; scripts rarely
  get there). Backfill canon ("established later — don't contradict") waits for newest-first backfill
  rendering in M6. Packets use the committed world's state (mood, constraints) for scripted items.
- M5: a softened MOCK/HOSTILE/TROLL item is re-rendered once and then kept (the spec sends it on to the
  route's fallback model). The LLM-ism and hard-line lexicons live in `ProseChecks`, not content.
- M5: offline cost and cache-hit checks are projections (`ProjectedMeter`: 4 chars/token, haiku prices,
  a repeated prefix billed as a cache read).
- M5: `sim.model` is a named interface so `narrative` can read the world; the harness gained an
  after-batch hook and its clock; `JobKinds` names the job kinds.

- M6: headlines and weather in the batch come from the offline sources (fixture headlines,
  `FakeWeather`) until M10 wires RSS and Open-Meteo. Images (§5.13) wait for M12.
- M6: a one-shot run that finds the lease held records its DEFERRED `batch_run` row through `SqlAdmin`
  (it has no lease to write through the lane).
- M6: events of importance ≥ 6 that have no rendered post get a recap line built from the event's own
  words (the renderer writes the rest).
- M6: a batch resets jobs left RUNNING by a killed run before it renders: it holds the lease, so nobody
  else is running them. Backfill render jobs run newest first, scripts earliest first.

- M7: no Playwright smoke test yet (SPEC §8): it would add a browser download to CI. The same path is
  covered by `LivePlayTests` and `ApiTests` end to end, and was checked by hand in the browser. Proposed
  for M8 or M9.
- M7: the right rail has a live world clock and contacts; the weather chip and "talked about in
  Chicagoland" wait for M10, presence dots and Messenger for M8, like-meters for M11, birthdays for later.
- M7: hooks implement `comment_on_older_post` and `pull_scripted_post_forward`; the DM hook comes with
  Messenger (M8).
- M7: live responses are written as scripted items timed at least 12 minutes out; the Pacer moves each
  one up to its release time. Whatever it never releases (R left, a restart) airs later as background,
  so nothing depends on the Pacer's in-memory state.
- M7: R's post or comment is written with a quick heuristic reading, so it shows at once; the
  `appraisal` call's reading drives the considered phase a moment later. Offline, the heuristic is the
  appraisal.
- M7: a live render that fails is handed to the job queue at backfill priority, so the batch finishes it.
- M7: writes while this process doesn't hold the writer lease (right after a restart) answer 503 with
  Retry-After instead of 500; the SPA tells R to try again.
- M7: the live-play controllers are web-only, so the one-shot modes (genesis, batch, bake-off) start
  without them (`OneShotContextTests`).
- M7: `.claude/launch.json` starts the offline backend and the Vite dev server for previews.

## Open questions for R
- Provider keys: which providers will you use? Put their keys in `.env` and run
  `./gradlew :backend:liveTest` (well under a cent at default models) to close M2's live ACs, including
  two unverified Anthropic details (Appendix C note).
