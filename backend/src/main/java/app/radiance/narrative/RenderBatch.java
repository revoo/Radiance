package app.radiance.narrative;

import java.util.List;

/** What the renderer returns for a batch of briefs (SPEC §5.10 output schema). */
public record RenderBatch(List<Item> items) {

  /**
   * @param gist ≤ 15 words, neutral; feeds memories and "today so far"
   * @param recapLine one gossipy line when the brief asked for it, else empty
   */
  public record Item(String briefId, String text, String gist, String recapLine, List<Fact> introducedFacts) {}

  /** @param subject a handle such as {@code @sam} */
  public record Fact(String subject, String statement, boolean major) {}
}
