# 0019 — Nightly SQLite backups to R2, without an SDK

Status: accepted · 2026-11-02 · SPEC §12 M9, ADR 0018

## Context
On one server, the database must never exist in only one place. A raw file copy of a live WAL database
can be torn. R2 is S3-compatible with a generous free tier. R's R2 keys aren't there on day one, and a
backup that silently stays on the same disk is the failure to design against.

## Decision
- **Snapshot with `VACUUM INTO`**, a consistent copy taken while the app runs, without needing the writer
  lease. Check the copy with `PRAGMA integrity_check`, gzip it, then upload.
- **Run in-process** at `radiance.backup.cron`, and at startup if the last backup is overdue, with a
  one-shot `backup` run mode for manual runs. Status lives in a small JSON file beside the backups, not
  in the database being backed up.
- **Retention:** 14 daily copies plus 8 weekly ones in R2; 3 recent ones on local disk, which also covers
  the time before keys exist.
- **Missing keys are loud, not fatal.** Local snapshots continue. An ERROR banner goes to the log at
  startup and on every run, the one-shot command exits non-zero, and the SPA shows a red banner.
- **Restore runs before Spring starts**, so nothing has the database open. It refuses while another process
  holds a live writer lease, verifies the download, and moves the current database aside instead of
  deleting it.
- **A small S3 client on `java.net.http`**: SigV4, PUT, GET, HEAD, LIST and DELETE, as ADR 0008 does for
  LLMs. No AWS SDK, no `rclone` sidecar. Tested against AWS's published signing vector and a real local
  HTTP server standing in for R2.

## Consequences
- We own about 200 lines of request signing; multipart upload isn't supported, so one backup is limited
  to R2's 5 GB single-PUT maximum, far above this database's size.
- Backups aren't encrypted by us: the bucket stays private, and the content is a simulated world.
- A backup is only as fresh as last night: a day of play is the most a total server loss can cost.
