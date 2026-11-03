# 0022 — One browser smoke test, with Playwright

Status: accepted · 2026-11-03 · from SPEC §8, deferred at M7

## Context
SPEC §8 asks for one Playwright smoke test against the `offline` profile: log in, the feed is already there, post, a
reaction within about 10 seconds, then a comment. It was put off at M7 because it adds a browser download to CI. Since
then every milestone's real-path tests have stopped at the HTTP layer, and bugs in how the SPA and the server meet were
only found by hand.

## Decision
- **`@playwright/test` as a dev dependency of the frontend, Chromium only.** Nothing from it ships.
- **One test, `frontend/e2e/smoke.spec.ts`,** against the real backend on the `offline` profile with its own throwaway
  database, and the Vite dev server in front of it. It costs nothing and calls no outside service.
- **Its own CI job,** in parallel with the others; the image waits for it. Browser download and the backend's start add
  about two minutes to that job, not to the whole run.

## Consequences
- A broken login, feed, post or stream now fails CI before an image is published.
- One more thing to keep up to date. It stays at one test: anything finer belongs in Vitest or the backend's tests.
