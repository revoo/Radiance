# 0021 — Media goes through the app, kept on disk and in R2

Status: accepted · 2026-11-03 · SPEC §5.13, §12 M12; follows ADR 0018 and ADR 0019

## Context
SPEC §5.13 was written for the Cloudflare plan: browsers upload to R2 with presigned URLs, through the AWS SDK, and
the bucket's CORS policy allows the SPA's origin. ADR 0018 moved the app to one always-on server, and ADR 0019 replaced the
SDK with a small S3 client. Presigning now means a new signing path and a CORS setting to keep right, to avoid a
server load that's negligible for one player.

## Decision
- **Same origin.** The SPA still downsizes each photo to ≤ 1600 px with a 400 px thumbnail, then PUTs both to
  `/api/media/{id}/upload`, behind the login and CSRF like every other write. `GET /api/media/{id}` streams the bytes.
- **The disk serves; R2 keeps.** `R2BackedMediaStore` writes to `data/media` and to `media/` in the backups bucket. Reads come
  from disk, and a miss fills in from R2, as after a restore onto a new server. Retention only ever touches `backups/`.
- **Nothing from a client is trusted.** Types come from the bytes, not the claim; only JPEG, PNG and WebP are accepted, never
  SVG; each size has a cap; storage keys are ours.
- **Workers AI over plain HTTP**, like every other provider (ADR 0008). `FakeImageGenerator` draws an SVG, so the server
  needs no fonts or imaging libraries. SVGs we generate are served with a locked-down CSP.
- **With no keys it all still works,** on local files and placeholders, with a warning in the log.

## Consequences
- No CORS or presigning to get wrong, and photos are served as fast as the disk.
- Every byte passes through the app, which is fine for one player and would need another look with many.
- Losing the disk loses nothing in R2. Photos and the database come back separately: a restore brings the database, and
  photos fill in as they're asked for.
