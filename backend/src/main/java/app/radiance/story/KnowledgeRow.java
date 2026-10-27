package app.radiance.story;

/** Who knows a PRIVATE or SECRET thing, and since when (SPEC §5.6). */
public record KnowledgeRow(Subject subjectType, String subjectId, String actorId, long since) {

  public enum Subject {
    ARC,
    FACT,
    EVENT
  }
}
