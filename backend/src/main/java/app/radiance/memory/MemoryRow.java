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
  /** Remembered from a DM: tagged {@code dm:<thread>}, and stays in that thread (SPEC §5.6). */
  public static boolean isFromADm(String tagsJson) {
    return tagsJson != null && tagsJson.contains("\"dm:");
  }

  public enum Kind {
    EPISODE,
    PERIOD_SUMMARY
  }
}
