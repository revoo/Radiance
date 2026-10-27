package app.radiance.kernel;

/** How a post, comment or DM came to exist. */
public enum ContentOrigin {
  SCRIPT,
  LIVE,
  BACKFILL,
  USER
}
