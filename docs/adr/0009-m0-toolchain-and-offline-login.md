# 0009 — M0 toolchain versions and the offline dev login

Status: accepted · 2026-10-27 · SPEC §0, §3.4, §5.17, M0

## Context
SPEC §3.4 names the stack but not exact versions, and two M0 facts needed checking: whether jqwik runs
on the Boot-managed JUnit Platform, and how "offline boots with no environment variables" squares
with "credentials come from env" (§5.17).

## Decision
- **Backend:** Gradle 9.8.2 (wrapper, distribution checksum pinned), Java 25 toolchain, Spring Boot
  4.1.2 (latest 4.1 patch on 2026-10-27), Spring Modulith 2.1.2 (built against Boot 4.1.2), jqwik
  1.10.2. Boot 4.1.2 manages JUnit 6.0.3; jqwik's engine runs on it (verified by a smoke property), so
  property tests use jqwik rather than seeded loops. Modulith: `spring-modulith-api` at compile time,
  `spring-modulith-starter-test` for `verify()`.
- **Frontend:** Node 24 LTS, React 19.3, Vite 8, Tailwind 4, TanStack Query 5, React Router 8, Vitest 5,
  ESLint 10. All versions pinned exactly. **TypeScript is pinned to 6.0.x**, not 7: typescript-eslint
  8.72 declares `typescript >=4.8.4 <6.1.0`.
- **Login:** `radiance.security.dev-login` is true only in the `offline` profile. With it on and no
  `RADIANCE_USERNAME`/`RADIANCE_PASSWORD_BCRYPT`, the app uses a built-in dev login and logs a loud
  warning. With it off, missing credentials fail startup.

## Consequences
- Upgrading TypeScript to 7 waits for typescript-eslint support.
- The dev login must never be enabled outside `offline`; a test covers the fail-fast path.
