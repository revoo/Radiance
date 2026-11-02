# Radiance on the server

One Linode (Debian 13, x86_64, 4 GB RAM, 2 CPUs, 80 GB), running Docker and a shared Caddy from
`/opt/hosting`. Radiance lives in `/opt/hosting/radiance` and answers at
`https://radiance.enigmatica.app`. Why it's built this way: ADR 0018 and ADR 0019.

```
/opt/hosting/
  compose.yaml            the `hosting` project; includes Radiance (root-owned)
  caddy/Caddyfile         the shared Caddy's sites
  radiance/
    compose.yaml          from deploy/compose.yaml
    .env                  secrets, from deploy/env.template (chmod 600)
    data/                 radiance.db, backups/, prompts/
```

Images are built by GitHub Actions and published to `ghcr.io/revoo/radiance` when `main` is pushed: `:main`
plus `:sha-<commit>`. The server only pulls. Don't build here; Gradle and npm need more memory than the app.

## First deploy

Steps 2 and 7 touch files or services other apps share. Steps marked **sudo** need root.

1. **Get the files onto the server.** From a checkout of the repository:

   ```bash
   scp deploy/compose.yaml claude@172.239.56.243:/opt/hosting/radiance/compose.yaml
   scp deploy/env.template claude@172.239.56.243:/opt/hosting/radiance/.env
   ```

2. **Point the hosting project at the image (sudo).** `/opt/hosting/compose.yaml` still has a placeholder that
   builds on the server. In that file, delete these two lines:

   ```yaml
     radiance:
       build: ./radiance
   ```

   and add this at the top of the file:

   ```yaml
   include:
     - radiance/compose.yaml
   ```

3. **Fill in the secrets.** On the server:

   ```bash
   cd /opt/hosting/radiance && chmod 600 .env && mkdir -p data
   docker run --rm httpd:2-alpine htpasswd -bnBC 12 "" 'your password' | tr -d ':\n'
   ```

   Put the username and that hash in `.env`. **Keep the hash in single quotes**: Compose expands `$` in `.env`,
   and an unquoted hash silently becomes a different value. The LLM keys are optional, and the R2 keys can wait
   (see [Backups](#backups)).

4. **Pull the image.**

   ```bash
   cd /opt/hosting && docker compose pull radiance
   ```

   If it says access denied, the package is private: make it public in the package's settings on GitHub, or
   `docker login ghcr.io` with a token that has `read:packages`.

5. **Create the world**, once. `--authored` uses the prose written at build time, with no LLM calls:

   ```bash
   cd /opt/hosting && docker compose run --rm radiance genesis --seed 7 --authored --import
   ```

   That also runs the first batch: two weeks of history and the first script.

6. **Start Radiance.** Nothing can reach it from outside yet.

   ```bash
   cd /opt/hosting && docker compose up -d radiance
   docker compose ps radiance
   ```

   Wait for `healthy`, and read the log. Expect the backup banner until the R2 keys are in.

7. **Add the site to Caddy**, and reload it. A reload keeps the other sites up; there's no need to restart:

   ```bash
   cat /path/to/repo/deploy/Caddyfile.radiance >> /opt/hosting/caddy/Caddyfile   # or paste it in, with a blank line first
   cd /opt/hosting && docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile
   ```

   Caddy gets a certificate for the new name on its own, as it did for `test.enigmatica.app`. The name is proxied
   by Cloudflare, so keep its SSL mode on **Full (strict)**: *Flexible* causes a redirect loop.

8. **Check it from your phone**: log in, onboard, and the feed is already there.

## Updating

```bash
cd /opt/hosting && docker compose pull radiance && docker compose up -d radiance
```

The app stops cleanly and releases its writer lease, so the new one takes over at once. Sessions are in memory,
so you'll log in again. To roll back, pin a commit in `/opt/hosting/radiance/.env`, say `RADIANCE_TAG=sha-abc1234`,
and run the same command. Newer migrations aren't undone by rolling back: restore a backup if a release's
migration is the problem.

## The nightly batch

It runs inside the app, at `radiance.batch.crons` (09:00 and 11:00 UTC), and catches up at startup if a run was
missed. The container restarts by itself, including after a reboot, so no timer is needed. Run one by hand with:

```bash
cd /opt/hosting && docker compose run --rm radiance batch nightly
```

It records `DEFERRED` if the app is running, and the app then runs it. Each run leaves a row in `batch_run`.

## Backups

Every night at 08:30 UTC, the app takes a consistent snapshot (`VACUUM INTO`), checks it, gzips it and uploads
it to R2. It keeps 14 daily and 8 weekly copies on R2, and 3 on the server in `data/backups/`.

### Adding the R2 keys

Until these are in, backups stay on this disk, and the app says so: an error banner in the log at startup and
on every backup, and a red banner in the app.

1. In Cloudflare, under **R2**, create a bucket such as `radiance-backups`. Leave public access off.
2. Under **R2 → Manage API tokens**, create a token with **Object Read & Write**, limited to that bucket.
3. In `/opt/hosting/radiance/.env`, set `CF_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` and
   `R2_BACKUP_BUCKET`.
4. Run `cd /opt/hosting && docker compose up -d radiance`. The first upload happens within about a minute. Look
   for `Backup uploaded to backups/radiance-…` in `docker compose logs radiance`. The banner in the app clears
   within 15 minutes, or on a page reload.

### Backing up by hand

This is safe while the app runs:

```bash
cd /opt/hosting && docker compose run --rm radiance backup
```

It exits 0 when the backup reached R2, 3 when it's only on the server because keys are missing, and 1 on failure.

### Restoring

```bash
cd /opt/hosting
docker compose run --rm radiance restore --list      # what's on the server and on R2
docker compose stop radiance
docker compose run --rm radiance restore latest      # or a name from the list, or --file data/backups/<file>
docker compose up -d radiance
```

Restore refuses to run while the app holds the writer lease. It checks the backup before touching anything, and
moves the current database to `data/pre-restore-<time>/` rather than deleting it. Remove that folder once you're
happy. The app applies any newer migrations when it starts. You lose whatever happened after the backup.

**On a new server**: install Docker, repeat steps 1–4 of the first deploy with the same `.env`, run `restore
latest`, then steps 6–7. Point DNS at the new address.

CI runs this whole drill on every milestone branch, on the real image (`deploy/smoke-test.sh`): a backup while the
app runs, to an S3 server that checks signatures; the data directory deleted; a restore; and the app serving
the same world and accepting writes. R2 itself is first exercised when your keys go in, so after adding them
it's worth doing one real restore check:

```bash
cd /opt/hosting && docker compose run --rm radiance restore --list
```

and confirm tonight's backup is listed on R2.

## Checking on it

```bash
cd /opt/hosting
docker compose ps
docker compose logs --tail 100 radiance
docker stats --no-stream
```

The app's heap is capped at 768 MB and the container at 1200 MB, so one app can't starve the others. Logs rotate
at 3 × 10 MB. `/api/director/backups` and `/director/pacer` (signed in) show backup status and live pacing.

## Server hardening

Checked on 2026-11-02: SSH is key-only and root can't log in with a password, and only ports 22, 80 and 443 are
listening. Two things worth adding, if you haven't another plan for them:

- **Automatic security updates**: `unattended-upgrades` isn't installed (`sudo apt install unattended-upgrades`).
- **A firewall**: a Linode Cloud Firewall allowing 22, 80 and 443. Docker bypasses `ufw` for published ports, so
  a firewall outside the box is the simpler one.
