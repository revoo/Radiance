package app.radiance.memory;

/** A memory, created by the engine when the event it remembers airs (SPEC §5.7). */
public record MemoryRow(
    String id,
    String ownerId,
    long worldTime,
    Kind kind,
    String text,
    int importance,
    String participantsJson,
    String tagsJson,
    String arcId,
    String eventId,
    boolean compacted) {
  public enum Kind {
    EPISODE,
    PERIOD_SUMMARY
  }
}
