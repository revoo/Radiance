# 0015 — A content module and the def schemas

Status: accepted · 2026-10-27 · SPEC P7, §3.5, §5.2–§5.5, §6.2, Appendix A, M3

## Context
Quirks, events, arcs, constraint tags, the rules files and the roster are read by `cast`, `story`, `sim`,
`live`, `graph` and `narrative`. Putting each def type in the module that "owns" it would create cycles
(arcs start events, quirks weight arcs, events need constraint tags). SPEC §3.5 lists no module for
content, and Appendix A's examples mix snake_case (A.1–A.7) and kebab-case (A.8, §6.2's roster).

## Decision
- A new module, **`content`**, depends only on `kernel`. It holds the def records (`Defs`, `Rules`,
  `Roster`), the vocabularies they're checked against (`Vocab`), the selector grammar, the theme linter,
  and a loader that validates everything as one library. Domain modules depend on it; it depends on none.
- **One library, all problems at once:** parsing collects every error (file, line, path), then the
  validator checks cross-references — quirks ↔ arcs/events, constraint tags and their parameters,
  arc graphs (phases reachable, probabilities summing to 1, terminal phases with an entry event, no
  silent endings), knowers ↔ roles, effects keys, calendar and weather triggers, selectors, the roster
  rules of §6.2 (30 residents, 15/15, ages, handles, symmetric partners and family, invariant 11) and
  the blacklist rules of §5.5. Startup fails with the full list.
- **Strict YAML:** unknown keys fail (typos don't silently vanish); absent booleans mean false.
- **Key style follows the spec's examples:** kebab-case for `cast/roster.yaml` and
  `rules/temperament.yaml`, snake_case for everything else. Def folders take one def per file or a list.
- Additions the spec implies but doesn't list: `category` on every arc (§5.5 says each arc names one),
  `traditions` in event conditions (faith holidays, §6.1), `knows` in the roster (decision 12), event
  `tags` for the planner's `excludes_event_tags`, and a `sensitive` flag on arcs.

## Consequences
- Content can be edited without touching code, and a broken edit is caught at startup with its location.
- `Vocab` is the single list of legal names; adding a calendar date or tag is a one-line change there.
- The library has 58 event defs rather than ≈40: calendar and faith observances need their own gates.
