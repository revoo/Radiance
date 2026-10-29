package app.radiance.live;

import app.radiance.content.Vocab;
import app.radiance.kernel.Json;
import app.radiance.llm.Block;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.LlmRequest;
import app.radiance.llm.Part;
import app.radiance.llm.Priority;
import app.radiance.llm.Purpose;
import app.radiance.sim.UserActions.Reading;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * How R's post or comment reads to the residents (SPEC §5.9): one structured call on the
 * {@code appraisal} route, inline as the first step of the considered phase, cached by content hash.
 * If the call fails, a neutral reading stands in, so R's action never waits on a retry.
 */
public final class Appraiser {

  /** The appraisal's output: tone intensities 0–1, politics −1…1 with confidence, and the rest. */
  public record ContentAppraisal(
      double humor, double warmth, double intellect, double pretentiousness, double bragging, double vulnerability,
      double positivity, double edginess, double preachiness, double effort, double flirtiness, double controversy,
      boolean asksQuestion, String stance, List<String> topics, List<String> mentions, double politicalEconomic,
      double politicalSocial, double politicalConfidence) {}

  private static final int CACHE = 500;

  private final LlmGateway gateway;
  private final Duration timeout;
  private final Map<String, Reading> cache = new LinkedHashMap<>(64, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Reading> e) {
      return size() > CACHE;
    }
  };

  public Appraiser(LlmGateway gateway, Duration timeout) {
    this.gateway = gateway;
    this.timeout = timeout;
  }

  /** How {@code text} reads; {@code onWhat} is the post or comment it answers, for comments. */
  public Reading read(String text, String onWhat) {
    String key = hash((onWhat == null ? "" : onWhat) + "\u0000" + text);
    synchronized (cache) {
      var hit = cache.get(key);
      if (hit != null) return hit;
    }
    var prompt = new StringBuilder();
    if (onWhat != null) prompt.append("It replies to:\n<context>\n").append(onWhat).append("\n</context>\n\n");
    prompt.append("Rate how this social-network ").append(onWhat == null ? "post" : "comment")
        .append(" comes across, each tone feature 0–1. Controversy 0–1. Political axes −1…1 with your confidence 0–1.")
        .append(" stance: one of ").append(String.join(", ", stances())).append(" (for a post, SUPPORT).")
        .append(" Topics from: ").append(String.join(", ", new TreeSet<>(Vocab.TOPICS))).append('.')
        .append(" mentions: the @handles it names.\n<text>\n").append(text).append("\n</text>");
    var result = gateway.call(new LlmRequest<>(Purpose.APPRAISAL,
        List.of(new Block("You appraise social-network content neutrally and return structured features.")),
        List.of(Part.text(prompt.toString())), ContentAppraisal.class, 400, timeout, Priority.LIVE));
    var a = result.ok() ? clean(result.value()) : heuristic(text, onWhat != null);
    var reading = reading(a);
    synchronized (cache) {
      cache.put(key, reading);
    }
    return reading;
  }

  public static Reading reading(ContentAppraisal a) {
    var f = new TreeMap<String, Double>();
    f.put("humor", a.humor());
    f.put("warmth", a.warmth());
    f.put("intellect", a.intellect());
    f.put("pretentiousness", a.pretentiousness());
    f.put("bragging", a.bragging());
    f.put("vulnerability", a.vulnerability());
    f.put("positivity", a.positivity());
    f.put("edginess", a.edginess());
    f.put("preachiness", a.preachiness());
    f.put("effort", a.effort());
    f.put("flirtiness", a.flirtiness());
    return new Reading(java.util.Collections.unmodifiableMap(f), a.topics(), a.controversy(), a.stance(), Json.write(a));
  }

  /** Clamps what a model returned to the vocabulary and ranges (validated ourselves, SPEC §5.10). */
  static ContentAppraisal clean(ContentAppraisal a) {
    var topics = a.topics() == null ? List.<String>of()
        : a.topics().stream().map(t -> t.toLowerCase(Locale.ROOT)).filter(Vocab.TOPICS::contains).distinct().toList();
    String stance = a.stance() == null ? "SUPPORT" : a.stance().toUpperCase(Locale.ROOT);
    if (!stances().contains(stance)) stance = "SUPPORT";
    return new ContentAppraisal(c(a.humor()), c(a.warmth()), c(a.intellect()), c(a.pretentiousness()), c(a.bragging()),
        c(a.vulnerability()), c(a.positivity()), c(a.edginess()), c(a.preachiness()), c(a.effort()), c(a.flirtiness()),
        c(a.controversy()), a.asksQuestion(), stance, topics, a.mentions() == null ? List.of() : a.mentions(),
        Math.max(-1, Math.min(1, a.politicalEconomic())), Math.max(-1, Math.min(1, a.politicalSocial())),
        c(a.politicalConfidence()));
  }

  private static List<String> stances() {
    var out = new ArrayList<String>();
    for (var s : Vocab.Stance.values()) if (s != Vocab.Stance.REACT_ONLY && s != Vocab.Stance.META) out.add(s.name());
    return out;
  }

  // -------------------------------------------------------------------------------------- offline

  /** Offline play reads R's words with simple, deterministic cues instead of a model. */
  public static void registerFake(FakeResponses fakes) {
    fakes.register(ContentAppraisal.class, call -> {
      String all = call.allContent();
      int a = all.lastIndexOf("<text>\n"), b = all.lastIndexOf("\n</text>");
      String text = a >= 0 && b > a ? all.substring(a + 7, b) : all;
      return heuristic(text, all.contains("<context>"));
    });
  }

  private static final Pattern WORD = Pattern.compile("[\\p{L}']+");
  private static final Pattern MENTION = Pattern.compile("@([a-z0-9_.]+)");

  static ContentAppraisal heuristic(String text, boolean comment) {
    String t = text.toLowerCase(Locale.ROOT);
    var words = new ArrayList<String>();
    var m = WORD.matcher(t);
    while (m.find()) words.add(m.group());
    int n = Math.max(1, words.size());
    double longWords = words.stream().filter(w -> w.length() >= 9).count() / (double) n;
    double humor = cue(t, 0.25, "lol", "haha", "lmao", "😂", "🤣", "jk", "😅", "rofl", "hilarious");
    double warmth = cue(t, 0.2, "love", "thanks", "thank you", "miss", "proud", "congrat", "hug", "❤", "<3", "glad", "happy",
        "so good", "amazing", "sweet", "🥰");
    double negative = cue(t, 0.25, "hate", "awful", "worst", "ugh", "terrible", "sucks", "angry", "annoyed", "stupid", "idiot",
        "ridiculous", "pathetic", "trash", "garbage");
    double swear = cue(t, 0.35, "fuck", "shit", "damn", "hell", "crap", "wtf", "bs");
    double positivity = clamp(0.5 + warmth * 0.6 + humor * 0.2 - negative * 0.8 - swear * 0.3);
    double caps = text.chars().filter(Character::isUpperCase).count() / (double) Math.max(1, text.length());
    double edginess = clamp(negative * 0.6 + swear + (caps > 0.4 && text.length() > 8 ? 0.3 : 0)
        + cue(t, 0.3, "unpopular opinion", "fight me", "honestly", "overrated", "wake up"));
    double pretentious = clamp(longWords * 1.5 + cue(t, 0.25, "actually", "arguably", "objectively", "nuance", "per se",
        "literally", "frankly", "as someone who", "in my experience", "i'd argue"));
    double bragging = cue(t, 0.3, "promotion", "promoted", "finally", "crushed", "nailed", "new car", "bought", "my new",
        "just got", "first place", "won");
    double vulnerable = cue(t, 0.3, "feeling", "sad", "lonely", "anxious", "tough", "struggling", "rough day", "tired",
        "stressed", "scared");
    double preachy = cue(t, 0.3, "should", "need to", "people need", "everyone should", "you have to", "stop ");
    double flirt = cue(t, 0.35, "😉", "😘", "cute", "gorgeous", "beautiful", "date", "drinks sometime", "handsome");
    boolean question = t.contains("?");
    double political = cue(t, 0.35, "trump", "biden", "election", "tax", "abortion", "gun", "immigra", "vote", "police",
        "congress", "mayor", "governor", "democrat", "republican", "liberal", "conservative");
    double controversy = clamp(Math.max(political, edginess * 0.5));
    double effort = clamp(text.length() / 280.0);
    double intellect = clamp(0.2 + longWords + (text.length() > 200 ? 0.2 : 0));

    var topics = new ArrayList<String>();
    topic(topics, t, "politics", "trump", "biden", "election", "vote", "congress", "mayor", "governor", "democrat", "republican");
    topic(topics, t, "economy", "economy", "inflation", "prices", "jobs report", "recession", "interest rate");
    topic(topics, t, "tech", "code", "coding", "software", "app", "apps", "ai", "laptop", "phone", "computer", "startup",
        "bug", "programming", "developer", "iphone", "android", "chatgpt");
    topic(topics, t, "cars", "car", "cars", "truck", "engine", "mustang", "tesla", "oil change", "mechanic", "brakes");
    topic(topics, t, "home_improvement", "drywall", "renovat", "paint", "deck", "kitchen", "basement", "home depot");
    topic(topics, t, "fitness", "gym", "run", "ran", "running", "runner", "workout", "marathon", "lift", "lifting", "yoga",
        "5k", "10k", "half marathon", "miles", "bike ride", "cycling", "swim", "crossfit", "pr");
    topic(topics, t, "food", "pizza", "dinner", "lunch", "breakfast", "brunch", "taco", "burger", "cook", "cooking",
        "recipe", "beef", "coffee", "restaurant", "bbq", "grill", "hot dog", "deep dish", "bakery", "ramen", "sushi");
    topic(topics, t, "parenting", "kids", "daughter", "son ", "toddler", "baby", "school pickup");
    topic(topics, t, "pets", "dog", "cat", "puppy", "kitten", "vet");
    topic(topics, t, "music", "concert", "album", "song", "band", "playlist");
    topic(topics, t, "movies_tv", "movie", "movies", "show", "netflix", "episode", "series", "film", "hbo", "binge");
    topic(topics, t, "gaming", "game", "xbox", "playstation", "nintendo", "steam");
    topic(topics, t, "outdoors", "hike", "hiking", "lake", "lakefront", "camping", "trail", "fishing", "beach", "kayak",
        "park", "sunset", "weather", "snow", "rain");
    topic(topics, t, "travel", "trip", "flight", "vacation", "airport", "hotel");
    topic(topics, t, "health", "doctor", "sick", "flu", "covid", "surgery", "knee");
    topic(topics, t, "local", "chicago", "cta", "the loop", "wrigley", "naperville", "evanston", "oak park", "berwyn",
        "lakefront", "logan square", "wicker park", "pilsen", "lincoln park", "metra", "the l", "chicagoland");
    topic(topics, t, "personal_finance", "budget", "401k", "rent", "mortgage", "savings", "paycheck");

    String stance = "SUPPORT";
    if (comment) {
      if (t.contains("congrat")) stance = "CONGRATS";
      else if (t.contains("sorry for your loss") || t.contains("so sorry") || t.contains("thinking of you")) stance = "CONDOLENCE";
      else if (swear > 0 && negative > 0) stance = "HOSTILE";
      else if (cue(t, 1, "disagree", "wrong", "no way", "nope", "not true", "doubt", "come on") > 0) stance = "DISAGREE";
      else if (humor > 0 && negative > 0) stance = "MOCK";
      else if (flirt > 0) stance = "FLIRT";
      else if (question) stance = "QUESTION";
      else if (humor > 0) stance = "JOKE";
      else if (cue(t, 1, "agree", "exactly", "so true", "same", "100%") > 0) stance = "AGREE";
      else if (cue(t, 1, "you should", "try ", "i'd recommend", "pro tip") > 0) stance = "ADVICE";
    }
    var mentions = new ArrayList<String>();
    var mm = MENTION.matcher(t);
    while (mm.find()) mentions.add(mm.group(1));
    return new ContentAppraisal(humor, clamp(0.25 + warmth), intellect, pretentious, bragging, vulnerable, positivity,
        edginess, preachy, effort, flirt, controversy, question, stance, topics, mentions, 0, 0, political > 0 ? 0.2 : 0);
  }

  private static double cue(String t, double each, String... cues) {
    int hits = 0;
    for (String c : cues) if (has(t, c)) hits++;
    return clamp(hits * each);
  }

  private static void topic(List<String> topics, String t, String topic, String... cues) {
    for (String c : cues) {
      if (has(t, c)) {
        topics.add(topic);
        return;
      }
    }
  }

  private static final Map<String, Pattern> CUES = new java.util.concurrent.ConcurrentHashMap<>();

  /** A word cue matches at the start of a word ("hell" isn't in "hello" but "renovat" is in "renovating"). */
  private static boolean has(String t, String cue) {
    if (!Character.isLetter(cue.charAt(0))) return t.contains(cue);
    var p = CUES.computeIfAbsent(cue, c -> Pattern.compile("(?<![\\p{L}])" + Pattern.quote(c.strip())
        + (c.length() <= 4 || c.endsWith(" ") ? "(?![\\p{L}])" : "")));
    return p.matcher(t).find();
  }

  private static double c(double v) {
    return clamp(v);
  }

  private static double clamp(double v) {
    return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
  }

  private static String hash(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
