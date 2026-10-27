package app.radiance.kernel;

/** Whether an item's text exists yet (SPEC §5.10). */
public enum RenderStatus {
  PLANNED,
  RENDERED,
  FAILED,
  DROPPED
}
