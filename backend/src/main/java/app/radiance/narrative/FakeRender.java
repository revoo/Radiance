package app.radiance.narrative;

import app.radiance.kernel.Json;
import app.radiance.llm.FakeResponses;
import java.util.ArrayList;
import java.util.Map;

/**
 * Offline render output (SPEC §5.10 FakeLlm): plain, recognizable text built from each brief — the
 * beat for posts, a stock line per stance for comments — so the offline app reads like a feed. Never
 * introduces facts.
 */
public final class FakeRender {

  private FakeRender() {}

  static final Map<String, String> LINES = Map.ofEntries(
      Map.entry("SUPPORT", "love this"), Map.entry("CONGRATS", "congrats!! so happy for you"),
      Map.entry("CONDOLENCE", "so sorry, thinking of you"), Map.entry("JOKE", "lol of course you did"),
      Map.entry("AGREE", "100% this"), Map.entry("DISAGREE", "nah, not buying it"),
      Map.entry("TROLL", "imagine thinking this"), Map.entry("MOCK", "this is embarrassing tbh"),
      Map.entry("HOSTILE", "get a grip, seriously"), Map.entry("FLIRT", "looking good 😉"),
      Map.entry("QUESTION", "wait what happened?"), Map.entry("ADVICE", "have you tried calling them?"),
      Map.entry("SELF_PIVOT", "same thing happened to me last week"), Map.entry("HYPE", "LET'S GO"),
      Map.entry("META", "here we go again"), Map.entry("FOR", "about time"), Map.entry("AGAINST", "unbelievable"));

  public static void register(FakeResponses fakes) {
    fakes.register(RenderBatch.class, call -> render(call.allContent()));
    fakes.register(JudgeAnswer.class, call -> new JudgeAnswer(false));
    fakes.register(Compacted.class, call -> new Compacted(java.util.List.of("A quiet stretch: work, friends, the usual."),
        "Keeping busy lately."));
    fakes.register(RelationSummary.class, call -> new RelationSummary("They get along fine."));
  }

  static RenderBatch render(String content) {
    var items = new ArrayList<RenderBatch.Item>();
    int from = content.indexOf("<briefs>"), to = content.indexOf("</briefs>");
    if (from < 0 || to < 0) return new RenderBatch(items);
    var briefs = Json.tree(content.substring(from + 8, to).strip());
    for (var b : briefs) {
      String kind = b.path("kind").asString("");
      String stance = b.path("stance").asString("");
      String author = b.path("author").asString("");
      String beat = b.path("beats").path(0).asString("");
      String text;
      if (kind.equals("COMMENT") || kind.equals("REPLY") || kind.equals("DM")) {
        text = LINES.getOrDefault(stance, beat.isEmpty() ? "ha" : beat);
      } else {
        text = beat.isEmpty() ? "thinking about stuff" : beat.substring(0, 1).toUpperCase() + beat.substring(1);
        if (b.has("headline")) text = LINES.getOrDefault(stance, "huh") + ": " + b.path("headline").asString();
      }
      String gist = author + " " + (stance.isEmpty() ? "posted" : stance.toLowerCase().replace('_', ' ')) + ": "
          + (beat.length() > 60 ? beat.substring(0, 60) : beat);
      String recap = b.path("recapLine").asBoolean(false) ? author + " has news: " + beat : "";
      items.add(new RenderBatch.Item(b.path("briefId").asString(), text, gist.strip(), recap, java.util.List.of()));
    }
    return new RenderBatch(items);
  }
}
