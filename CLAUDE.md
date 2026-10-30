# Radiance — notes for Claude Code
Source of truth: SPEC.md. Status: PROGRESS.md. Decisions: docs/adr/.

## Non-negotiables
- Simulate in code, narrate with the LLM. No LLM calls in the planning path (SPEC §2 P1).
- Canon lives in the DB. Prose may add only MINOR facts, which get recorded (P2).
- Committed time moves only through WorldEngine.advanceTo(), which airs scripted items. The nightly
  batch scripts a day ahead and live play scripts seconds ahead; both go through airing (P3).
- No spoilers: nothing is visible to R or canon for live prose until it airs.
- All DB writes go through the writer lane as a UnitOfWork, under the fenced writer lease (P4).
- R's interactions never wait on the job queue; the Pacer decides when he sees responses.
- The roster is fixed (SPEC §6.2): never add, drop or rename residents. Every resident is straight and
  cisgender; romance and attraction always pair a man and a woman.
- Every LLM call goes through LlmGateway by purpose. No provider SDK, type, model id or parameter name
  outside its adapter or config. Tests never call real LLM or image APIs.
- SQL must also run on D1: no BEGIN/COMMIT, ≤100 params per statement, index every query path.
- Validate every model output against its schema ourselves, whatever the provider promises.
- Domain code never sets sampling or reasoning parameters; model aliases carry them. The Anthropic
  adapter never sends temperature/top_p/top_k, and Haiku 5.5 aliases disable thinking explicitly.
- Blacklisted arcs never start or get seeded, and their themes never reach prose.
- Real people, unsanitized (SPEC P9): never soften, disclaim or moralize residents' words. The hard
  lines: adults only, non-explicit sex, no real private individuals, no invented quotes about real people.
- Non-live LLM work is resumable jobs that park when a budget window runs dry (SPEC §5.15).
- New dependency, process or external service ⇒ ADR first. Complexity must earn its keep.

## Commands
- Backend: `./gradlew bootRun --args='--spring.profiles.active=offline'` · `./gradlew check`
- Nightly batch, one-shot: `./gradlew batch` (runs `batch nightly` and exits)
- Simulation harness: `./gradlew simulate --args='--days=30 --seed=42'`
- Model bake-off: `./gradlew llm-bakeoff --args='--purpose=render-thread --models=haiku,<candidate>'`
- Live LLM contract tests (opt-in, costs cents; keys from env or .env): `./gradlew :backend:liveTest`
- Frontend: `npm run dev` · `npm test` · `npm run build`

## Conventions
- Package by feature; the domain core is plain Java (no Spring, no I/O).
- Records for values, sealed interfaces for intents and events; inject Clock and RandomSource.
- Content defs live in backend/src/main/resources/content and are validated at startup.

## Workflow
- Milestones are in SPEC §12. After each: tests green, PROGRESS.md updated, commit. Stop at ⏸ REVIEW.
- If the spec contradicts reality, stop and report with evidence.
- Testing priorities (R, regressions are a known fragile point), on top of the tests the work needs:
  1. every bug fix ships with a regression test that fails without the fix;
  2. some tests run the real path — the real loop thread, the real security chain on a real server,
     the real startup modes — not only hand-driven shortcuts, which hid most of M7's bugs;
  3. each milestone's AC lines (SPEC §12) become tests;
  4. pure logic with tricky rules (the Pacer, the math, formatters) is tested thoroughly.
- Your own token budget runs out: commit at every green step and keep PROGRESS.md's Resume here block
  current. A new session reads CLAUDE.md, PROGRESS.md and `git log -5` first, then resumes.
