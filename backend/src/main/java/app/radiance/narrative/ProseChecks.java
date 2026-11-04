package app.radiance.narrative;

import app.radiance.content.ContentLibrary;
import app.radiance.kernel.Visibility;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.LlmRequest;
import app.radiance.llm.Part;
import app.radiance.llm.Priority;
import app.radiance.llm.Purpose;
import app.radiance.sim.model.Person;
import app.radiance.sim.model.SimWorld;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The per-item validation of rendered prose (SPEC §5.10): length, the constraint linter with
 * {@code must_not_reveal} rules and the blacklisted themes, introduced facts, LLM-isms, softening and
 * the hard lines. Lexicons find candidates; the {@code linter-judge} route decides. Each problem comes
 * back in words the re-render can act on.
 */
public final class ProseChecks {

  /** One problem with an item, phrased for the re-render. */
  public record Problem(String kind, String detail) {}

  /** "no wait, I'm Lisa and I went to my parents'": the model catching itself, in the post. */
  static boolean slipped(String author, String text) {
    if (SLIP.matcher(text).find()) return true;
    if (author == null || author.isBlank()) return false;
    return Pattern.compile("(?i)(^|\\balso )" + Pattern.quote(author.split(" ")[0]) + ",").matcher(text).find();
  }

  static final Pattern SLIP = Pattern.compile("(?i)\\bno wait\\b|\\.\\.\\.\\s*(wait no|i mean)\\b");

  /** Five words in a row is saying it again; shorter runs are just how people talk. */
  private static final int REPEAT_RUN = 5;
  /** Quoting a few of someone's words back at them is normal; this many is copying them. */
  private static final int COPY_RUN = 7;

  /**
   * What {@code handle} is saying again from their own earlier lines in this packet's conversation: a run of
   * words, or one of their catchphrases. Null if nothing. Regression: Jessica signed off every text "sorry not
   * sorry" and gave R her address three times, whatever he said.
   */
  static String repeats(String text, String packet, String handle, List<String> catchphrases) {
    String said = ownLines(packet, handle);
    String mine = " " + plain(text) + " ";
    for (String phrase : catchphrases) {
      String p = " " + plain(phrase) + " ";
      if (!p.isBlank() && mine.contains(p) && said.contains(p)) return phrase;
    }
    var words = plain(text).split(" ");
    for (int i = 0; i + REPEAT_RUN <= words.length; i++) {
      String run = String.join(" ", java.util.Arrays.copyOfRange(words, i, i + REPEAT_RUN));
      if (said.contains(" " + run + " ")) return run;
    }
    // Regression: Rick sent Nick's whole comment back, down to Nick's aside about a Jewel coupon
    String everyone = ownLines(packet, null);
    for (int i = 0; i + COPY_RUN <= words.length; i++) {
      String run = String.join(" ", java.util.Arrays.copyOfRange(words, i, i + COPY_RUN));
      if (everyone.contains(" " + run + " ")) return run;
    }
    return null;
  }

  /** Whether this is, whole, something {@code handle} already sent. Regression: Rick sent the same text word for word, again and again, for weeks. */
  static boolean sentAlready(String text, String packet, String handle) {
    String mine = plain(text);
    return mine.split(" ").length >= REPEAT_RUN && ownLines(packet, handle).contains(" " + mine + " ");
  }

  /** Everything {@code handle} (or with null, anyone) has said in this packet's conversation, in plain words, space-delimited. */
  private static String ownLines(String packet, String handle) {
    int from = Math.max(packet.indexOf("<conversation>"), packet.indexOf("<thread>"));
    if (from < 0) return "";
    int to = packet.indexOf("<briefs>", from);
    var own = new StringBuilder();
    for (String line : packet.substring(from, to < 0 ? packet.length() : to).split("\\R")) {
      String l = line.strip();
      if (handle != null && l.startsWith(handle + ": ")) own.append(' ').append(plain(l.substring(handle.length() + 2)));
      if (handle == null && l.startsWith("@") && l.contains(": ")) own.append(' ').append(plain(l.substring(l.indexOf(": ") + 2)));
    }
    return own + " ";
  }

  private static String plain(String text) {
    return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}' ]+", " ").replaceAll("\\s+", " ").strip();
  }

  /** "Sox lost AGAIN last night", in November. */
  static final Pattern BASEBALL_GAME = Pattern.compile("(?i)\\b(cubs|sox)\\b[^.!?]{0,30}\\b(lost|won|blew|beat|bullpen|game)\\b|\\b(cubs|sox)\\s+(bullpen|game)\\b");

  static boolean baseballOutOfSeason(String text, java.time.LocalDate date) {
    int month = date.getMonthValue();
    return (month < 4 || month > 10) && BASEBALL_GAME.matcher(text).find();
  }
  static final Pattern LLM_ISMS = Pattern.compile("(?i)\\b(delve|delving|tapestry|a testament to|navigating the|"
      + "it'?s important to|as someone who|in today'?s world|let'?s all be kind|everyone'?s valid|just my two cents,? "
      + "everyone|at the end of the day,? we|journey of|embrace the|a reminder that we|stay safe out there,? everyone|"
      + "sending (?:love and )?light to all|let'?s be kind to one another|i hope this helps)\\b");
  static final List<String> SEXUAL = List.of("naked", "nude", "porn", "orgasm", "genitals", "sex with", "had sex",
      "blowjob", "erection", "masturbat");
  static final List<String> MINORS = List.of("kid", "kids", "child", "children", "teen", "teenager", "daughter", "son",
      "boy", "girl", "student", "minor");
  static final List<String> THREATS = List.of("i'll kill", "should be shot", "hope he dies", "hope she dies",
      "deserves to die", "someone should shoot");
  static final List<String> SECRET_TERMS = List.of("dating", "seeing each other", "together", "boyfriend", "girlfriend",
      "hooking up", "affair", "couple", "kissing", "sleeping with", "secret", "in love", "a thing", "an item");

  private final ContentLibrary lib;
  private final LlmGateway gateway;
  private final SimWorld world;
  private final boolean softeningCheck;

  public ProseChecks(ContentLibrary lib, LlmGateway gateway, SimWorld world, boolean softeningCheck) {
    this.lib = lib;
    this.gateway = gateway;
    this.world = world;
    this.softeningCheck = softeningCheck;
  }

  public List<Problem> check(ContextPacketBuilder.Target target, RenderBatch.Item item, Person author, String lengthBand,
      Set<String> involved, long t) {
    var out = new ArrayList<Problem>();
    String text = item.text() == null ? "" : item.text().strip();
    if (text.isEmpty()) {
      out.add(new Problem("empty", "write the item; it came back empty"));
      return out;
    }
    int words = text.split("\\s+").length;
    int max = 2 * bandMax(lengthBand);
    if (words > max) out.add(new Problem("length", "too long for this voice: keep it under " + max + " words"));

    // blacklisted themes: never, from anyone (SPEC §5.5)
    for (var hit : lib.themeLinter().hits(text)) {
      if (judge("Does this text mention or allude to " + hit.term() + "?", text)) {
        out.add(new Problem("blacklist", "never mention or allude to " + hit.term() + ", in any form"));
      }
    }
    // the author's own constraint tags
    if (author != null && !author.user) {
      for (String tag : world.activeTags(author.id, t, lib)) {
        int i = tag.indexOf(':');
        var def = lib.constraints().get(i < 0 ? tag : tag.substring(0, i));
        if (def == null || def.linter() == null || def.linter().judge() == null) continue;
        String param = i < 0 ? "" : tag.substring(i + 1);
        var other = world.people.get(param);
        String paramWords = other != null ? other.name : param.replace('_', ' ');
        if (!Lexicon.hits(text, def.linter().lexicon())) continue;
        String q = def.linter().judge().replace("{name}", author.name).replace("{param}", paramWords);
        if (judge(q, text)) out.add(new Problem("constraint", "this breaks a constraint — " + q.replace("Does this text", "it must not")
            .replace("?", "")));
      }
      // secrets they don't know: must_not_reveal (SPEC §5.6)
      for (var a : world.arcs.values()) {
        if (!a.active() || a.visibility == Visibility.PUBLIC) continue;
        if (a.involves(author.id) || world.knows("ARC", a.id, author.id)) continue;
        var names = new ArrayList<String>();
        for (String id : a.roles.values()) {
          var p = world.people.get(id);
          if (p != null) {
            names.add(p.name.split(" ")[0]);
            names.add("@" + p.handle);
          }
        }
        if (!Lexicon.hits(text, names) || !Lexicon.hits(text, SECRET_TERMS)) continue;
        String who = String.join(" and ", a.roles.values().stream().map(id -> world.people.containsKey(id)
            ? world.people.get(id).name : id).toList());
        if (judge("Does this text reveal or hint that " + who + " are in a " + a.defId.replace('_', ' ') + "?", text)) {
          out.add(new Problem("secret", author.name + " doesn't know about " + who + "; don't reveal or hint at it"));
        }
      }
    }
    for (var f : item.introducedFacts() == null ? List.<RenderBatch.Fact>of() : item.introducedFacts()) {
      if (f.major()) out.add(new Problem("major-fact", "don't introduce major life facts (\"" + f.statement() + "\")"));
    }
    if (slipped(author == null ? null : author.name, text)) out.add(new Problem("slip", "the writing lost track of who's talking; just write it as them"));
    if (baseballOutOfSeason(text, java.time.Instant.ofEpochMilli(t).atZone(world.zone).toLocalDate())) {
      out.add(new Problem("season", "baseball is off season right now: no games, wins, losses or bullpens"));
    }
    var isms = LLM_ISMS.matcher(text);
    if (isms.find()) out.add(new Problem("llm-ism", "sounds like a chatbot (\"" + isms.group() + "\"); write like a person"));
    if (softeningCheck && target.stance() != null && Set.of("MOCK", "HOSTILE", "TROLL").contains(target.stance())
        && judge("Is this text softened — polite, hedged, apologetic or disclaiming — instead of plainly "
            + target.stance().toLowerCase() + "?", text)) {
      out.add(new Problem("softened", "the brief says " + target.stance().toLowerCase() + "; write it that way, no hedging"));
    }
    boolean sexual = Lexicon.hits(text, SEXUAL);
    if ((sexual && Lexicon.hits(text, MINORS)) || Lexicon.hits(text, THREATS) || (sexual && words > 40)) {
      if (judge("Is this text sexually explicit, does it sexualize anyone under 18, or does it threaten a real person?", text)) {
        out.add(new Problem("hard-line", "crosses a hard line: keep sex non-explicit, nobody under 18, no threats"));
      }
    }
    return out;
  }

  /** Words per length band (SPEC §5.2 voice card); the check allows twice this. */
  static int bandMax(String band) {
    if (band == null) return 40;
    return switch (band) {
      case "TERSE" -> 12;
      case "SHORT" -> 35;
      case "MEDIUM" -> 70;
      case "LONG" -> 160;
      default -> 40;
    };
  }

  /** The {@code linter-judge} route; when it can't answer, assume yes and let the item re-render. */
  boolean judge(String question, String text) {
    String prompt = lib.prompt("linter-judge").replace("{{question}}", question).replace("{{text}}", text);
    var r = gateway.call(new LlmRequest<>(Purpose.LINTER_JUDGE, List.of(), List.of(new Part.Text(prompt)), JudgeAnswer.class,
        40, Duration.ofSeconds(30), Priority.SCRIPT));
    return !r.ok() || r.value().yes();
  }

  /** Case-insensitive whole-word matching over a list of terms. */
  static final class Lexicon {
    private Lexicon() {}

    static boolean hits(String text, List<String> terms) {
      for (String term : terms) {
        String words = Pattern.quote(term.strip()).replace(" ", "\\E\\s+\\Q");
        if (Pattern.compile("(?<![\\p{L}\\p{N}])" + words + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
            .matcher(text).find()) return true;
      }
      return false;
    }
  }
}
