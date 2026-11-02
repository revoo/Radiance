# 0018 — Hosting on one Linode VPS, with SQLite and a shared Caddy

Status: accepted · 2026-11-02 · SPEC §12 M9 (its "Your call" on hosting), R's decision. Supersedes the
Cloudflare plan in SPEC §3, §4.2 and §12 M9 for production; ADR 0003 stands for the SQL dialect.

## Context
SPEC planned an edge Worker, D1, R2 and Cloudflare Containers. R decided one server is simpler than
several managed services for a single-player game, and the box will host other projects too. It runs
Debian 13 on x86_64 with 4 GB of RAM, 2 CPUs and 80 GB of disk, with Docker and a shared Caddy already
running from `/opt/hosting`. `radiance.enigmatica.app` is on Cloudflare DNS, proxied.

## Decision
- **One always-on container** per app. Radiance is its own Compose project in `/opt/hosting/radiance` (its compose
  file, `.env` and `data/`), joined to the shared `hosting_default` network, so updating it touches nothing else.
- **SQLite on local disk is the production database.** No D1, Worker, `/sql/batch` gateway or `D1Sql`.
  The `Sql` port and `JdbcSql` stay as they are, and so do the D1-friendly SQL rules: they cost little and
  keep the door open.
- **The existing Caddy terminates HTTPS** and proxies to `radiance:8080`. Radiance publishes no ports
  and ships no second Caddy. Caddy's mounts stay untouched, so **the app serves the SPA itself** from
  the jar: the same origin for pages, API and cookies.
- **Images are built in CI and pulled.** GitHub Actions publishes `ghcr.io/revoo/radiance` on pushes to
  `main` and milestone branches, tagged by branch and commit. The server never builds: Gradle and npm use more memory than the app. Build stages run on the
  CI runner's own architecture and only the runtime stage is per-platform, so `linux/amd64` and
  `linux/arm64` both stay cheap and safe.
- **The nightly batch runs in-process** on the existing scheduler with overdue detection, inside a
  container that restarts on its own. An OS timer adds nothing while the app is always up; the one-shot
  command stays for manual runs.
- **A `prod` profile** requires real credentials, marks cookies Secure, and trusts `X-Forwarded-*` from
  Caddy. The container's memory is capped so one app can't starve the others on the box.
- **Backups leave the server** nightly (ADR 0019).

## Consequences
- Dropped from M9: the Worker, D1, `D1Sql`, the `wrangler dev` contract suite, Cron Container triggers
  and cold-start CDS/AOT work (the app stays warm).
- One box is one point of failure: an outage stops live play until it's back or restored elsewhere. Fine
  for a single player, and the restore procedure works on any Docker host.
- In-memory sessions end at each deploy, so R logs in again.
- With Cloudflare proxying, the client address Radiance sees is a Cloudflare edge, so login rate limiting
  is per edge address rather than per client. Still bounded for a single-user app; Caddy could pass
  `CF-Connecting-IP` later if it matters.
- If this ever had to scale: Postgres behind the `Sql` port, a real queue behind the job table, and app
  containers behind a load balancer sharing a lease. None of that is built.
