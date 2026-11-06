# 0024 — Drop-in mode: nothing while R is away, a short busy show when he's on

Status: accepted · 2026-11-06 · changes SPEC §1 (online and offline), §5.1 (batch and backfill) and §5.12 (live news)

## Context
After the play tests, R decided week-long continuity isn't achievable with today's models, and the project is now something to drop
into for about ten minutes, for fun or to stir things up. The nightly batch spent money every night on days nobody read, and a login
after days away started a backfill that rendered up to 48 hours in full plus a script for the next day. Visits are short, so most
of that was never seen. R wants zero spend while he's away, and the most engaging possible ten minutes when he's on.

## Decision
`radiance.live.drop-in: true` (on in `prod` and `offline`) changes only how the existing pieces are scheduled and weighted:
- **No nightly batch.** The in-process scheduler stays off whatever `radiance.batch.enabled` says.
- **A cheap arrival.** A login's backfill writes only the last `radiance.live.arrival-window` (45m) in full, starting no earlier than
  where his last visit ended, and skips the rest of the gap structure-only (moods drift, arcs move on, nothing posts). It writes no
  script ahead: live chatter covers the visit. Old history stays where it was.
- **A news storm at login.** The session's news refresh runs at login, not an hour in, looks at the last 24 hours of headlines, and lets
  up to six residents take them on, landing in the first few minutes, by anyone who'd care, at any hour. Later refreshes stay at two.
- **Fights are the main event.** A session always opens on an argument, or picks one on a take on the news, which the poster answers.
  Arguments run five to nine turns and carry on more often. Comments go to news threads more than half the time.
- **R gets called out.** A new chatter beat, `CALLED_OUT`: someone DMs R to argue about something he said in public in the last three
  hours, quoting his real words. Code picks who: the likeliest to argue and the least fond of him first, and nobody twice a visit. In
  that thread, they stay combative until he gives ground or they've made their point six times, then wrap up their own way.
- **A busier room.** Moments rest a quarter as long, specials come up more often, beats come faster for the first five minutes, a few
  friends are always around even at 3 a.m., and in `prod` residents pay R more attention (`social.user-salience` 3.0, max share 0.8).
- **An open tab isn't R.** The browser stops heartbeats after ten minutes without input, so the session times out and spending stops.
  The next input beats at once and starts a new visit, news storm included.

## Consequences
- Logged out costs nothing: no batch, no session, no model calls, which a test checks over a simulated day. Backups to R2 still run.
- A visit costs more per minute than before, and much less per visit than a login backfill used to. Budgets still cap it.
- Continuity over days is no longer attempted: the world skips the gaps. Fixed facts and old posts stay.
- `drop-in: false` brings the believable mode back unchanged, the batch included.
