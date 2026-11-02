package app.radiance.narrative;

import app.radiance.content.ContentLibrary;
import app.radiance.llm.BudgetExhaustedException;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.LlmRequest;
import app.radiance.llm.Part;
import app.radiance.llm.Priority;
import app.radiance.llm.Purpose;
import app.radiance.news.Headline;
import app.radiance.news.HeadlineRepository;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies new headlines in batches of 40 (SPEC §5.12). Whatever it can't finish, because a call failed or a budget
 * ran out, stays unclassified for the next run. Every answer is checked here, whatever the provider promises.
 */
public final class HeadlineClassifier {

  /** The model's answer. */
  public record Batch(List<Entry> items) {}

  /** One headline, by its number in the prompt. */
  public record Entry(int n, String region, List<String> topics, double controversy, double salience, String sensitivity,
      double economic, double social, double confidence) {}

  private static final Logger log = LoggerFactory.getLogger(HeadlineClassifier.class);
  static final int BATCH_SIZE = 40;
  private static final int MAX_SNIPPET = 140;
  private static final int MAX_TOPICS = 3;
  private static final Set<String> REGIONS = Set.of("LOCAL", "NATIONAL", "WORLD");
  private static final Set<String> SENSITIVITIES = Set.of("NORMAL", "SENSITIVE", "TRAGIC");
  /** The topics residents have interests in; fixture-headlines.yaml uses the same words. */
  private static final Set<String> TOPICS = Set.of("politics", "economy", "local", "personal_finance", "science", "tech",
      "true_crime", "food", "cars", "parenting", "health", "celebrity", "movies_tv", "travel", "sports", "sports:bears",
      "sports:bulls", "sports:cubs", "sports:white_sox", "sports:red_stars", "pets", "outdoors", "music",
      "home_improvement", "gaming", "fitness", "faith");

  private final Sql sql;
  private final WriterLane lane;
  private final ContentLibrary lib;
  private final LlmGateway gateway;

  public HeadlineClassifier(Sql sql, WriterLane lane, ContentLibrary lib, LlmGateway gateway) {
    this.sql = sql;
    this.lane = lane;
    this.lib = lib;
    this.gateway = gateway;
  }

  /** Returns how many were classified. */
  public int classify(List<Headline> rows, long now) {
    int done = 0;
    for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
      var chunk = rows.subList(from, Math.min(rows.size(), from + BATCH_SIZE));
      List<Headline> classified;
      try {
        classified = one(chunk, now);
      } catch (BudgetExhaustedException e) {
        log.info("Headline classification paused by a budget window; {} left for later", rows.size() - from);
        break;
      }
      if (classified.isEmpty()) continue;
      var repo = new HeadlineRepository(sql);
      lane.run(() -> sql.commit(repo.saveClassified(new UnitOfWork(), classified)));
      done += classified.size();
    }
    return done;
  }

  private List<Headline> one(List<Headline> chunk, long now) {
    var r = gateway.call(new LlmRequest<>(Purpose.HEADLINE_CLASSIFY, List.of(), List.of(new Part.Text(prompt(chunk))),
        Batch.class, 4000, Duration.ofSeconds(90), Priority.BACKGROUND));
    if (!r.ok() || r.value().items() == null) {
      log.warn("Headline classification failed: {}", r.outcome());
      return List.of();
    }
    return apply(chunk, r.value(), now);
  }

  private String prompt(List<Headline> chunk) {
    var lines = new StringBuilder();
    for (int i = 0; i < chunk.size(); i++) {
      var h = chunk.get(i);
      lines.append(i + 1).append(". [").append(h.source()).append("] ").append(h.title());
      if (h.snippet() != null) {
        lines.append(" — ").append(h.snippet(), 0, Math.min(MAX_SNIPPET, h.snippet().length()));
      }
      lines.append('\n');
    }
    return lib.prompt("headline-classify").replace("{{headlines}}", lines.toString().strip());
  }

  /** Entries with an unknown number or region are dropped, and those headlines stay unclassified. */
  static List<Headline> apply(List<Headline> chunk, Batch batch, long now) {
    var out = new ArrayList<Headline>();
    var seen = new HashSet<Integer>();
    for (var e : batch.items()) {
      if (e == null || e.n() < 1 || e.n() > chunk.size() || !seen.add(e.n()) || !known(REGIONS, e.region())) continue;
      var h = chunk.get(e.n() - 1);
      var topics = e.topics() == null ? List.<String>of()
          : e.topics().stream().filter(t -> known(TOPICS, t)).distinct().limit(MAX_TOPICS).toList();
      double confidence = clamp(e.confidence(), 0, 1);
      out.add(new Headline(h.id(), h.url(), h.source(), h.title(), h.snippet(), h.publishedAt(), h.fetchedAt(), e.region(),
          topics, clamp(e.controversy(), 0, 1), clamp(e.salience(), 0, 1),
          known(SENSITIVITIES, e.sensitivity()) ? e.sensitivity() : "NORMAL",
          Map.of("economic", clamp(e.economic(), -1, 1) * confidence, "social", clamp(e.social(), -1, 1) * confidence), now));
    }
    return out;
  }

  /** Set.of throws on a null lookup, and a model can leave any field out. */
  private static boolean known(Set<String> words, String word) {
    return word != null && words.contains(word);
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static final Pattern LINE = Pattern.compile("(?m)^(\\d+)\\. (.*)$");
  private static final Pattern LOCAL = Pattern.compile("(?i)chicago|illinois|cook county|\\bCTA\\b|naperville|evanston");
  private static final Pattern TRAGIC = Pattern.compile("(?i)\\bkilled\\b|\\bdead\\b|\\bdies\\b|shooting|massacre|fatal");

  /** Offline and in tests: a plausible answer from the words alone, the same every time. */
  static Batch fake(String content) {
    var items = new ArrayList<Entry>();
    var m = LINE.matcher(content);
    List<String> kinds = List.of("politics", "economy", "tech", "sports", "health", "celebrity");
    while (m.find()) {
      String text = m.group(2);
      int hash = Math.floorMod(text.hashCode(), 1000);
      String region = LOCAL.matcher(text).find() ? "LOCAL" : hash % 3 == 0 ? "WORLD" : "NATIONAL";
      items.add(new Entry(Integer.parseInt(m.group(1)), region, List.of(kinds.get(hash % kinds.size())),
          (hash % 100) / 100.0, 0.3 + (hash % 70) / 100.0, TRAGIC.matcher(text).find() ? "TRAGIC" : "NORMAL",
          ((hash % 21) - 10) / 10.0, ((hash % 17) - 8) / 8.0, 0.8));
    }
    return new Batch(items);
  }
}
