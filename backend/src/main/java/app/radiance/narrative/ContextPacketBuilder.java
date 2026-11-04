package app.radiance.narrative;

import app.radiance.cast.CastRepository;
import app.radiance.cast.PersonaRow;
import app.radiance.content.ContentLibrary;
import app.radiance.kernel.Json;
import app.radiance.kernel.Visibility;
import app.radiance.llm.Block;
import app.radiance.llm.PromptBudget;
import app.radiance.memory.MemoryRetrieval;
import app.radiance.messenger.DmRepository;
import app.radiance.news.HeadlineRepository;
import app.radiance.persistence.Sql;
import app.radiance.sim.Brief;
import app.radiance.sim.model.Edge;
import app.radiance.sim.model.Person;
import app.radiance.sim.model.SimWorld;
import app.radiance.social.CommentRow;
import app.radiance.social.PostRow;
import app.radiance.social.SocialRepository;
import app.radiance.story.EventRepository;
import app.radiance.story.FactRow;
import app.radiance.weather.WeatherBook;
import app.radiance.weather.WeatherRepository;
import app.radiance.world.Calendar;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Builds context packets (SPEC §5.10, Appendix B). The prefix — world bible and cast directory — is
 * byte-identical across every call and world-day, so providers can cache it; everything dated goes in
 * the content, which starts with Today. Sections are trimmed to the route's token budget.
 */
public final class ContextPacketBuilder {

  /** One render call's prompt: the cacheable prefix, the content, and the briefs it asks for, in order. */
  public record Packet(List<Block> prefix, String content, List<Brief> briefs, Map<String, Target> targets) {}

  /** What a brief renders into: a post, comment or DM row. */
  public record Target(String type, String id, String authorId, String stance, String eventId, boolean recap, long time) {}

  static final long DAY = 86_400_000L;
  static final int MEMORIES = 6;
  private static final java.time.format.DateTimeFormatter FACT_DATE = java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.US);
  private static final java.time.format.DateTimeFormatter DAY_MARKER = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US);
  /** A catchphrase they've used in this many of their latest items stays off their card for now. */
  static final int CATCHPHRASE_REST = 12;
  /** How much of what a brief answers is repeated in it, right where the writer looks. */
  static final int ANSWERING_CHARS = 300;
  static final int THREAD_TAIL = 20;
  static final int FACTS_PER_SUBJECT = 30;

  private final Sql sql;
  private final ContentLibrary lib;
  private final String networkName;
  private final SimWorld world;
  private final Map<String, PersonaRow> personas = new HashMap<>();
  private List<Block> prefix;

  public ContextPacketBuilder(Sql sql, ContentLibrary lib, String networkName, SimWorld world) {
    this.sql = sql;
    this.lib = lib;
    this.networkName = networkName;
    this.world = world;
    new CastRepository(sql).personas().forEach(p -> personas.put(p.actorId(), p));
  }

  // ------------------------------------------------------------------------------------- prefix

  /** World bible + cast directory: identical for every call, so it caches (SPEC §5.10). */
  public List<Block> prefix() {
    if (prefix != null) return prefix;
    String themes = lib.blacklist().stream().flatMap(b -> b.themeTerms().stream()).collect(Collectors.joining(", "));
    String bible = lib.prompt("world-bible").replace("{{network_name}}", networkName)
        .replace("{{blacklisted_themes}}", themes.isEmpty() ? "(none)" : themes);
    // ages as of the world's first day, so the directory never changes from one day to the next
    var ref = sql.queryOne("SELECT created_at FROM world", List.of(), r -> r.lng("created_at")).map(world::date)
        .orElse(LocalDate.of(2026, 10, 27));
    var dir = new StringBuilder("# Cast directory\n");
    for (var p : world.people.values()) {
      var row = sql.queryOne("SELECT occupation, current_city, display_name FROM actor WHERE id = ?", List.of(p.id),
          r -> new String[] {r.str("occupation"), r.str("current_city"), r.str("display_name")}).orElseThrow();
      dir.append("- @").append(p.handle).append(" — ").append(row[2]);
      if (p.birthDate != null) dir.append(", ").append(Period.between(LocalDate.parse(p.birthDate), ref).getYears());
      if (row[0] != null) dir.append(", ").append(row[0]);
      if (row[1] != null) dir.append(", ").append(row[1]);
      if (!p.user) {
        String heritage = Json.tree(personas.get(p.id).identityJson()).path("heritage").asString("");
        if (!heritage.isBlank()) dir.append(", ").append(heritage);
      }
      if (p.user) {
        dir.append("; the newest member (R)");
      } else {
        dir.append("; ").append(personality(p)).append("; voice: ").append(voiceMarkers(p));
      }
      dir.append('\n');
    }
    prefix = List.of(new Block(bible), new Block(dir.toString()));
    return prefix;
  }

  /** Six-ish words: the archetype if they have one, else the standout traits and quirks. */
  private String personality(Person p) {
    var id = Json.tree(personas.get(p.id).identityJson());
    String archetype = id.path("archetype").asString(null);
    var words = new ArrayList<String>();
    if (archetype != null && !archetype.isBlank()) words.add(archetype);
    p.traits.entrySet().stream().filter(e -> e.getValue() >= 75 || e.getValue() <= 25)
        .sorted(Map.Entry.comparingByKey()).limit(3)
        .forEach(e -> words.add((e.getValue() >= 75 ? "high " : "low ") + e.getKey().replace('_', ' ')));
    p.quirks.stream().limit(2).forEach(q -> words.add(q.replace('_', ' ')));
    return String.join(", ", words);
  }

  private String voiceMarkers(Person p) {
    var v = voice(p.id);
    return v.path("lengthBand").asString("?").toLowerCase() + ", emoji " + v.path("emojiLevel").asString("?").toLowerCase()
        + ", " + v.path("casing").asString("?").toLowerCase();
  }

  private JsonNode voice(String id) {
    var row = personas.get(id);
    return row == null ? Json.tree("{}") : Json.tree(row.voiceJson());
  }

  // ------------------------------------------------------------------------------------- threads

  /** RENDER_THREAD: the post (if it still needs writing) and every scripted comment on it. */
  public Packet thread(String postId, PromptBudget budget) {
    var social = new SocialRepository(sql);
    var post = social.post(postId).orElseThrow(() -> new IllegalArgumentException("no post " + postId));
    var comments = social.commentsOn(postId);
    var briefs = new ArrayList<Brief>();
    var targets = new LinkedHashMap<String, Target>();
    var authors = new LinkedHashSet<String>();
    authors.add(post.authorId());
    long t = post.createdAt();
    if (post.body() == null && renderable(post.renderStatus().name())) {
      boolean recap = recapWanted(post.eventId());
      briefs.add(postBrief(post, recap));
      targets.put(post.id(), new Target("POST", post.id(), post.authorId(), null, post.eventId(), recap, post.createdAt()));
    }
    for (var c : comments) {
      if (c.body() != null || !renderable(c.renderStatus().name()) || c.airStatus().name().equals("DROPPED")) continue;
      var b = Brief.parse(c.briefJson());
      briefs.add(commentBrief(c, b, answered(post, comments, c)));
      targets.put(c.id(), new Target(c.parentId() == null ? "COMMENT" : "REPLY", c.id(), c.authorId(), b.stance(), null,
          false, c.createdAt()));
      authors.add(c.authorId());
      if (b.toward() != null) authors.add(b.toward());
    }
    // nothing scripted for later: a reply mustn't see what hasn't been said yet
    long until = targets.values().stream().mapToLong(Target::time).max().orElse(Long.MAX_VALUE);
    var threadText = threadSoFar(post, comments.stream().filter(c -> c.body() == null || c.createdAt() <= until).toList());
    String content = fill("render-thread", today(t, post.headlineId(), authors, post.id()), people(authors, t, null),
        canon(authors, t, null), threadText, briefsJson(briefs, targets, authors));
    content = trim(content, budget);
    return new Packet(prefix(), content, briefs, targets);
  }

  /** What a comment answers: the words of its parent, or of the post. Null if those aren't written yet. */
  private static String answered(PostRow post, List<CommentRow> comments, CommentRow c) {
    if (c.parentId() == null) return post.body() == null || post.body().isBlank() ? null : quote(post.body());
    return comments.stream().filter(x -> x.id().equals(c.parentId()) && x.body() != null).findFirst().map(x -> quote(x.body())).orElse(null);
  }

  private static String quote(String text) {
    return text.length() <= ANSWERING_CHARS ? text : text.substring(0, ANSWERING_CHARS) + "…";
  }

  /** DM history in a render packet; older messages are in the thread summary. */
  static final int HISTORY = 60;

  /** RENDER_DM: the scripted messages in one thread, with the conversation so far. */
  public Packet dms(String threadId, PromptBudget budget) {
    var dms = new DmRepository(sql).messages(threadId);
    var thread = new DmRepository(sql).threads().stream().filter(x -> x.id().equals(threadId)).findFirst().orElseThrow();
    var authors = new LinkedHashSet<String>(List.of(thread.aId(), thread.bId()));
    var briefs = new ArrayList<Brief>();
    var targets = new LinkedHashMap<String, Target>();
    var convo = new StringBuilder();
    long t = dms.isEmpty() ? world.time : dms.getFirst().createdAt();
    // the latest messages, with older ones summarized (SPEC §5.11)
    // nothing scripted for later: Tim answered R by carrying on from his own message due the next day
    long until = dms.stream().filter(m -> m.body() == null && !m.airStatus().name().equals("DROPPED")).mapToLong(m -> m.createdAt()).max().orElse(Long.MAX_VALUE);
    var written = dms.stream().filter(m -> m.body() != null && m.createdAt() <= until).toList();
    var recent = new java.util.HashSet<>(written.subList(Math.max(0, written.size() - HISTORY), written.size()));
    if (thread.summary() != null && written.size() > HISTORY) {
      convo.append("(earlier: ").append(thread.summary()).append(")\n");
    }
    String last = null;
    java.time.LocalDate day = null;
    for (var m : dms) {
      if (m.body() != null) {
        if (m.createdAt() > until) continue;
        if (recent.contains(m)) {
          // Regression: with no dates, Rick's dinner "tonight" stayed tonight for three weeks
          var said = world.date(m.createdAt());
          if (!said.equals(day)) convo.append("-- ").append(said.format(DAY_MARKER)).append(" --\n");
          day = said;
          convo.append(handle(m.senderId())).append(": ").append(m.body()).append('\n');
        }
        t = m.createdAt();
        last = m.body();
      } else if (!m.airStatus().name().equals("DROPPED")) {
        t = Math.max(t, m.createdAt());
        var b = Brief.parse(m.briefJson());
        boolean answers = b.text() != null && b.text().startsWith("answer") && last != null;
        briefs.add(new Brief(b.defId(), b.category(), b.text(), b.stance(), b.toward(), b.interaction(), b.topics(),
            b.controversy(), b.drama(), extraWith(b.extra(), "briefId", m.id(), "kind", "DM", "author", handle(m.senderId()),
                "answering", answers ? quote(last) : null)));
        targets.put(m.id(), new Target("DM", m.id(), m.senderId(), b.stance(), null, false, m.createdAt()));
      }
    }
    String content = fill("render-dm", today(t, null, authors, null), people(authors, t, threadId), canon(authors, t, threadId),
        convo.isEmpty() ? "(no messages yet)" : convo.toString(), briefsJson(briefs, targets, authors));
    return new Packet(prefix(), trim(content, budget), briefs, targets);
  }

  private static boolean renderable(String renderStatus) {
    return renderStatus.equals("PLANNED") || renderStatus.equals("FAILED");
  }

  private boolean recapWanted(String eventId) {
    if (eventId == null) return false;
    return new EventRepository(sql).find(eventId).map(e -> e.importance() >= 6).orElse(false);
  }

  private Brief postBrief(PostRow post, boolean recap) {
    var b = Brief.parse(post.briefJson());
    return new Brief(b.defId(), b.category(), b.text(), b.stance(), b.toward(), b.interaction(), b.topics(),
        b.controversy(), b.drama(), extraWith(b.extra(), "briefId", post.id(), "kind", post.kind().name(), "author",
            handle(post.authorId()), "recapLine", recap));
  }

  private Brief commentBrief(CommentRow c, Brief b, String answering) {
    return new Brief(b.defId(), b.category(), b.text(), b.stance(), b.toward(), b.interaction(), b.topics(),
        b.controversy(), b.drama(), extraWith(b.extra(), "briefId", c.id(), "kind", c.parentId() == null ? "COMMENT" : "REPLY",
            "author", handle(c.authorId()), "replyTo", c.parentId() != null ? c.parentId() : c.postId(), "answering", answering));
  }

  private static Map<String, Object> extraWith(Map<String, Object> extra, Object... kv) {
    var m = new LinkedHashMap<String, Object>(extra);
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  // ------------------------------------------------------------------------------------- sections

  String today(long t, String headlineId, Set<String> people, String thisPost) {
    var local = Instant.ofEpochMilli(t).atZone(world.zone);
    var date = local.toLocalDate();
    int h = local.getHour();
    String part = h < 5 ? "late night" : h < 12 ? "morning" : h < 17 ? "afternoon" : h < 21 ? "evening" : "night";
    var sb = new StringBuilder();
    sb.append(date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US)).append(", ").append(date).append(", ")
        .append(part).append(" (").append(String.format("%02d:%02d", h, local.getMinute())).append("), ")
        .append(Calendar.season(date)).append(".\n").append("Chicago sports: ").append(Calendar.sports(date)).append(".\n");
    var weather = new WeatherBook(world.zone, lib.weather())
        .putAll(new WeatherRepository(sql).between(t - t % 3_600_000L - DAY, t + DAY));
    weather.at(t).ifPresent(w -> sb.append("Weather: ").append(w.describe()).append(".\n"));
    var near = new TreeSet<String>();
    for (int d = -2; d <= 3; d++) {
      for (String c : Calendar.triggers(date.plusDays(d))) near.add(c.replace('_', ' ') + (d == 0 ? " (today)" : d < 0 ? " (" + -d + "d ago)" : " (in " + d + "d)"));
    }
    if (!near.isEmpty()) sb.append("Around now: ").append(String.join(", ", near)).append(".\n");
    var headlines = new HeadlineRepository(sql).publishedBetween(t - 2 * DAY, t + 1).stream()
        .sorted(Comparator.comparingDouble(app.radiance.news.Headline::salience).reversed()).limit(4).toList();
    if (headlineId != null) {
      sql.queryOne("SELECT title FROM headline WHERE id = ?", List.of(headlineId), r -> r.str("title"))
          .ifPresent(title -> sb.append("This post shares: “").append(title).append("”\n"));
    }
    if (!headlines.isEmpty()) {
      sb.append("In the news: ").append(headlines.stream().map(x -> "“" + x.title() + "”")
          .collect(Collectors.joining("; "))).append(".\n");
    }
    var sofar = todaySoFar(t, people, thisPost);
    if (!sofar.isEmpty()) sb.append("Today so far:\n").append(sofar);
    return sb.toString().strip();
  }

  /**
   * Gists of the latest items today involving these people (SPEC §5.10: scripted "today so far"), for continuity:
   * only what everyone in this packet could have seen, and not the thread they're writing, which is there in full.
   */
  private String todaySoFar(long t, Set<String> people, String thisPost) {
    var date = world.date(t);
    long from = date.atStartOfDay(world.zone).toInstant().toEpochMilli();
    var rows = sql.query("SELECT c.author_id, c.gist, c.post_id, p.author_id AS post_author, CASE WHEN p.headline_id IS NULL THEN p.audience ELSE 'PUBLIC' END AS audience FROM comment c JOIN post p ON p.id = c.post_id"
        + " WHERE c.created_at >= ? AND c.created_at < ? AND c.gist IS NOT NULL AND c.air_status <> 'DROPPED' ORDER BY c.created_at DESC LIMIT 200",
        List.of(from, t), r -> new String[] {r.str("author_id"), r.str("gist"), r.str("post_id"), r.str("post_author"), r.str("audience")});
    var lines = new ArrayList<String>();
    for (var r : rows) {
      if (!people.contains(r[0]) || r[2].equals(thisPost)) continue;
      if (!r[4].equals("PUBLIC") && !people.stream().allMatch(x -> canSeePostsOf(x, r[3]))) continue;
      lines.add("- " + r[1] + "\n");
      if (lines.size() >= 8) break;
    }
    java.util.Collections.reverse(lines);
    return String.join("", lines);
  }

  private boolean canSeePostsOf(String reader, String author) {
    if (reader.equals(author)) return true;
    var e = world.edgeOrNull(reader, author);
    return e != null && e.connected;
  }

  String people(Set<String> ids, long t, String dmThread) {
    var sb = new StringBuilder();
    for (String id : ids) {
      var p = world.people.get(id);
      if (p == null) continue;
      sb.append(person(p, ids, t, dmThread)).append('\n');
    }
    return sb.toString().strip();
  }

  private String person(Person p, Set<String> involved, long t, String dmThread) {
    var sb = new StringBuilder("## @").append(p.handle).append(" — ").append(p.name);
    if (p.user) {
      sb.append(" (R, the newest member; never write as him)\n");
      return sb.toString();
    }
    sb.append('\n');
    String looks = Json.tree(personas.get(p.id).identityJson()).path("appearance").asString("");
    if (!looks.isBlank()) sb.append("Looks: ").append(looks.split(";")[0]).append(".\n");
    var v = voice(p.id);
    sb.append("Voice: ").append(v.path("lengthBand").asString("").toLowerCase()).append(" posts; emoji ")
        .append(v.path("emojiLevel").asString("").toLowerCase()).append("; casing ").append(v.path("casing").asString("").toLowerCase())
        .append("; ").append(v.path("punctuation").asString("")).append("; typos ")
        .append(Math.round(v.path("typoRate").asDouble(0) * 100)).append("%.\n");
    list(sb, "Slang", v.path("slang"));
    var catchphrases = restedCatchphrases(p.id);
    if (!catchphrases.isEmpty()) sb.append("Catchphrases (rarely, most posts have none): ").append(String.join(", ", catchphrases)).append('\n');
    list(sb, "Talks about", v.path("signatureTopics"));
    list(sb, "Never", v.path("neverDoes"));
    list(sb, "Sample posts", v.path("samplePosts"));
    list(sb, "Sample comments", v.path("sampleComments"));
    sb.append("Right now: ").append(mood(p)).append(doing(p, t)).append(".\n");
    underneath(sb, p, t);
    var constraints = constraintLines(p, t);
    if (!constraints.isEmpty()) sb.append("Constraints: ").append(String.join(" ", constraints)).append('\n');
    var arcs = new ArrayList<String>();
    for (var a : world.arcs.values()) {
      if (!a.active()) continue;
      boolean mine = a.involves(p.id);
      boolean knows = a.visibility == Visibility.PUBLIC || mine || world.knows("ARC", a.id, p.id);
      if (!knows || (!mine && a.roles.values().stream().noneMatch(involved::contains))) continue;
      String who = a.visibility == Visibility.PUBLIC ? "" : " — " + a.visibility.name().toLowerCase() + ", known only to "
          + knowers(a);
      arcs.add(a.summary + who);
    }
    if (!arcs.isEmpty()) sb.append("Storylines they know: ").append(String.join("; ", arcs)).append(".\n");
    var things = world.things.values().stream().filter(x -> p.id.equals(x.ownerId) && x.current())
        .map(x -> x.name + (x.visualDescription == null ? "" : " (" + x.visualDescription + ")")).toList();
    if (!things.isEmpty()) sb.append("Their things: ").append(String.join("; ", things)).append(".\n");
    var arcIds = world.arcsOf(p.id).stream().map(a -> a.id).collect(Collectors.toSet());
    var memories = new MemoryRetrieval(sql).top(p.id, t, involved, arcIds, Set.of(), 2 * MEMORIES).stream()
        .filter(m -> !app.radiance.memory.MemoryRow.isFromADm(m.tagsJson()) || (dmThread != null && m.tagsJson().contains("\"dm:" + dmThread + "\"")))
        .map(this::remembered).filter(x -> x != null).limit(MEMORIES).toList();
    if (!memories.isEmpty()) {
      sb.append("Remembers:\n");
      for (String m : memories) sb.append("- ").append(m).append('\n');
    }
    for (String other : involved) {
      if (other.equals(p.id)) continue;
      var e = world.edgeOrNull(p.id, other);
      var o = world.people.get(other);
      if (o == null) continue;
      sb.append("Sees @").append(o.handle).append(": ").append(e == null ? "a stranger" : relation(e)).append('\n');
    }
    var openings = openings(p.id);
    if (!openings.isEmpty()) sb.append("Recent openings to avoid: ").append(String.join(" / ", openings)).append('\n');
    return sb.toString();
  }

  private static void list(StringBuilder sb, String label, JsonNode arr) {
    if (!arr.isArray() || arr.isEmpty()) return;
    var items = new ArrayList<String>();
    arr.forEach(x -> items.add(x.asString()));
    sb.append(label).append(": ").append(label.startsWith("Sample") ? items.stream().map(x -> "“" + x + "”")
        .collect(Collectors.joining(" ")) : String.join(", ", items)).append('\n');
  }

  /** What they're doing, which colors a DM reply instead of delaying it (SPEC §5.11). */
  private String doing(Person p, long t) {
    if (p.location == null) return "";
    int hour = Instant.ofEpochMilli(t).atZone(world.zone).getHour();
    String where = p.location.startsWith("TRAVELING") ? "traveling" : switch (p.location) {
      case "WORK" -> "at work";
      case "OUT" -> "out and about";
      default -> hour >= 23 || hour < 6 ? "at home, about to sleep" : "at home";
    };
    return ", " + where;
  }

  private static String mood(Person p) {
    String mood = p.mood > 0.4 ? "in a great mood" : p.mood > 0.1 ? "in a decent mood" : p.mood > -0.2 ? "so-so"
        : p.mood > -0.5 ? "in a bad mood" : "miserable";
    String energy = p.energy > 0.65 ? "energetic" : p.energy < 0.35 ? "tired" : "okay energy";
    String stress = p.stress > 0.55 ? ", stressed" : "";
    return mood + ", " + energy + stress;
  }

  private String relation(Edge e) {
    double a = e.affinity;
    String feel = a >= 60 ? "adores them" : a >= 30 ? "likes them" : a >= 10 ? "fine with them" : a > -10 ? "neutral"
        : a > -35 ? "doesn't like them" : "can't stand them";
    var sb = new StringBuilder(e.kind.name().toLowerCase().replace('_', ' ')).append(", ").append(feel)
        .append(" (").append(Math.round(a)).append(")");
    var tags = e.tags.stream().filter(x -> !x.startsWith("~")).toList();
    if (!tags.isEmpty()) sb.append(", ").append(String.join(", ", tags));
    if (e.summary != null && !e.summary.isBlank()) sb.append(". ").append(e.summary);
    return sb.toString();
  }

  private String knowers(app.radiance.sim.model.Arc a) {
    var out = new ArrayList<String>();
    for (var p : world.people.values()) if (a.involves(p.id) || world.knows("ARC", a.id, p.id)) out.add("@" + p.handle);
    return String.join(", ", out);
  }

  /**
   * A memory in words. An event's own text is the template it was planned from ("a promotion announcement,
   * humble or not"), not what happened: use what its post turned out to say, and leave out one that never got written.
   */
  private String remembered(app.radiance.memory.MemoryRow m) {
    if (m.eventId() == null) return m.text();
    return sql.queryOne("SELECT gist FROM post WHERE event_id = ? AND gist IS NOT NULL LIMIT 1", List.of(m.eventId()), r -> r.str("gist")).orElse(null);
  }

  /**
   * What's going on under what they say (SPEC §1, the focus since v1.8): what they're insecure about, how it shows
   * and what they fish for, from content; and what they want, fear and believe, from genesis, which no prompt read
   * until now. One want and one fear at a time, turning over by the day, so they aren't all on about the same thing.
   */
  private void underneath(StringBuilder sb, Person p, long t) {
    if (p.user) return;
    var life = lib.innerLives().residents().get(p.handle);
    if (life != null) {
      sb.append("Underneath, never said out loud: insecure ").append(life.insecureAbout()).append("; when that's touched, ")
          .append(life.whenTouched()).append("; wants from people: ").append(life.wantsFromPeople()).append(".\n");
    }
    var row = personas.get(p.id);
    if (row == null) return;
    long day = world.date(t).toEpochDay();
    var mind = new ArrayList<String>();
    pick(row.desiresJson(), day, p.handle).ifPresent(x -> mind.add("wants: " + x));
    pick(row.fearsJson(), day, p.handle).ifPresent(x -> mind.add("afraid of: " + x));
    if (!mind.isEmpty()) sb.append("On their mind lately: ").append(String.join("; ", mind)).append(".\n");
    var values = new ArrayList<String>();
    Json.tree(row.valuesJson() == null ? "[]" : row.valuesJson()).forEach(x -> values.add(x.asString("")));
    if (!values.isEmpty()) sb.append("Believes in: ").append(String.join("; ", values)).append(".\n");
  }

  private static java.util.Optional<String> pick(String json, long day, String handle) {
    var all = new ArrayList<String>();
    Json.tree(json == null ? "[]" : json).forEach(x -> all.add(x.asString("")));
    return all.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(all.get((int) Math.floorMod(day + handle.hashCode(), (long) all.size())));
  }

  /** Their catchphrases, less any they've used lately. Regression: "I have receipts" in 92 of one person's items in a month. */
  private List<String> restedCatchphrases(String id) {
    var said = String.join(" ", sql.query("SELECT body FROM (SELECT body, created_at FROM post WHERE author_id = ? AND body IS NOT NULL AND air_status = 'AIRED'"
        + " UNION ALL SELECT body, created_at FROM comment WHERE author_id = ? AND body IS NOT NULL AND air_status = 'AIRED'"
        + " UNION ALL SELECT body, created_at FROM dm_message WHERE sender_id = ? AND body IS NOT NULL AND air_status = 'AIRED')"
        + " ORDER BY created_at DESC LIMIT " + CATCHPHRASE_REST, List.of(id, id, id), r -> r.str("body"))).toLowerCase(Locale.ROOT);
    return catchphrases(id).stream().filter(x -> !said.contains(x.toLowerCase(Locale.ROOT))).toList();
  }

  /** A voice card's catchphrases. */
  List<String> catchphrases(String id) {
    var out = new ArrayList<String>();
    voice(id).path("catchphrases").forEach(x -> out.add(x.asString("")));
    return out;
  }

  /** Each active tag's prose line (SPEC §5.5). */
  public List<String> constraintLines(Person p, long t) {
    var out = new ArrayList<String>();
    var tags = world.activeTags(p.id, t, lib);
    // single to everyone else, maybe, but a writer told both at once takes neither seriously
    boolean together = tags.stream().anyMatch(x -> x.startsWith("in_relationship:"));
    for (String tag : tags) {
      if (together && tag.equals("single")) continue;
      int i = tag.indexOf(':');
      String base = i < 0 ? tag : tag.substring(0, i);
      String param = i < 0 ? "" : tag.substring(i + 1);
      var def = lib.constraints().get(base);
      if (def == null || def.prose() == null) continue;
      var other = world.people.get(param);
      out.add(def.prose().replace("{name}", p.name).replace("{param}", other != null ? other.name : param.replace('_', ' '))
          .replace("{public_reason}", ""));
    }
    return out;
  }

  private List<String> openings(String id) {
    return sql.query("SELECT body FROM (SELECT body, created_at FROM post WHERE author_id = ? AND body IS NOT NULL AND air_status = 'AIRED'"
        + " UNION ALL SELECT body, created_at FROM comment WHERE author_id = ? AND body IS NOT NULL AND air_status = 'AIRED')"
        + " ORDER BY created_at DESC LIMIT 5", List.of(id, id), r -> r.str("body")).stream()
        .map(b -> String.join(" ", java.util.Arrays.stream(b.strip().split("\\s+")).limit(3).toList()))
        .map(s -> "“" + s + "”").toList();
  }

  String canon(Set<String> ids, long t, String dmThread) {
    var sb = new StringBuilder();
    // what came up in a DM stays in that DM. Regression: R told Amanda he read a book, and Daniel teased him for it;
    // Jessica brought their private toast and eggs up in public
    var facts = sql.query("SELECT f.subject_id, f.statement, f.status, m.thread_id, f.source, f.valid_from FROM fact f LEFT JOIN dm_message m ON m.id = f.origin_ref"
        + " WHERE f.status <> 'VOID' AND f.valid_from <= ? ORDER BY f.valid_from DESC", List.of(t), r -> new String[] {r.str("subject_id"),
        r.str("statement"), r.str("status"), r.isNull("thread_id") ? null : r.str("thread_id"), r.str("source"), String.valueOf(r.lng("valid_from"))});
    var per = new HashMap<String, Integer>();
    var seen = new java.util.HashSet<String>();
    for (var f : facts) {
      if (!ids.contains(f[0])) continue;
      if (f[3] != null && !f[3].equals(dmThread)) continue;
      // Regression: Ellie lost the same tooth seven times in a month. Each telling became a new fact, with no date to say it was over
      if (!seen.add(f[0] + "|" + f[1].strip().toLowerCase(Locale.ROOT))) continue;
      if (per.merge(f[0], 1, Integer::sum) > FACTS_PER_SUBJECT) continue;
      var p = world.people.get(f[0]);
      sb.append("- @").append(p == null ? f[0] : p.handle).append(" ").append(f[1]);
      if (FactRow.Status.PENDING.name().equals(f[2])) sb.append(" (earlier today)");
      else if (FactRow.Source.PROSE.name().equals(f[4])) sb.append(" (").append(world.date(Long.parseLong(f[5])).format(FACT_DATE)).append(')');
      sb.append('\n');
    }
    return sb.isEmpty() ? "(nothing beyond the cast directory)" : sb.toString().strip();
  }

  /** What a post's photo shows, so people can react to it: a dog gets talked about as a dog. */
  private java.util.Optional<String> photoOf(PostRow post) {
    if (post.mediaId() == null) return java.util.Optional.empty();
    return new app.radiance.media.MediaRepository(sql).byId(post.mediaId()).map(m -> m.description()).filter(d -> d != null && !d.isBlank());
  }

  private String arcOf(String postId) {
    return new SocialRepository(sql).post(postId).map(PostRow::arcId).orElse(null);
  }

  private String threadSoFar(PostRow post, List<CommentRow> comments) {
    var sb = new StringBuilder();
    sb.append(handle(post.authorId())).append(" (").append(post.kind().name().toLowerCase()).append(" post");
    photoOf(post).ifPresent(what -> sb.append(", the photo shows: ").append(what));
    sb.append("): ").append(post.body() != null ? post.body() : "(being written now; see briefs)").append('\n');
    var written = comments.stream().filter(c -> c.body() != null).toList();
    if (written.size() > THREAD_TAIL) {
      sb.append("(").append(written.size() - THREAD_TAIL).append(" earlier comments)\n");
      written = written.subList(written.size() - THREAD_TAIL, written.size());
    }
    for (var c : written) {
      sb.append(c.parentId() == null ? "  " : "    ").append(handle(c.authorId())).append(": ").append(c.body()).append('\n');
    }
    return sb.toString().strip();
  }

  private String briefsJson(List<Brief> briefs, Map<String, Target> targets, Set<String> authors) {
    var out = new ArrayList<Map<String, Object>>();
    for (var b : briefs) {
      var m = new LinkedHashMap<String, Object>();
      var x = b.extra();
      m.put("briefId", x.get("briefId"));
      m.put("kind", x.get("kind"));
      m.put("author", x.get("author"));
      if (x.containsKey("replyTo")) m.put("replyTo", x.get("replyTo"));
      if (x.get("answering") != null) m.put("answering", x.get("answering"));
      if (b.stance() != null) m.put("stance", b.stance());
      if (b.toward() != null) m.put("addressing", handle(b.toward()));
      if (b.text() != null) m.put("beats", List.of(b.text()));
      var mentions = x.get("mentions") instanceof List<?> l ? l.stream().map(o -> handle(String.valueOf(o))).toList() : List.of();
      if (!mentions.isEmpty()) m.put("mentions", mentions);
      var target = targets.get(String.valueOf(x.get("briefId")));
      var author = target == null ? null : world.people.get(target.authorId());
      if (author != null && !author.user) {
        m.put("tone", Map.of("mood", round(author.mood), "energy", round(author.energy)));
        m.put("lengthBand", voice(author.id).path("lengthBand").asString("SHORT"));
        var lines = new ArrayList<String>(mustNotReveal(author, authors));
        if (!lines.isEmpty()) m.put("constraints", lines);
      }
      if (x.get("headline") != null) m.put("headline", x.get("headline"));
      if (x.get("media") != null) {
        String arcId = target == null ? null : arcOf(target.id());
        m.put("media", app.radiance.sim.PhotoPrompts.forWriters(world, lib, target == null ? null : target.authorId(), b.defId(), arcId, String.valueOf(x.get("media"))));
      }
      m.put("recapLine", Boolean.TRUE.equals(x.get("recapLine")));
      out.add(m);
    }
    return Json.write(out);
  }

  /**
   * The secrets in this packet that {@code author} doesn't know: they must not reveal or hint at them
   * (SPEC §5.6). Secrets about people outside the packet don't come up at all.
   */
  List<String> mustNotReveal(Person author, Set<String> involved) {
    var out = new ArrayList<String>();
    for (var a : world.arcs.values()) {
      if (!a.active() || a.visibility == Visibility.PUBLIC) continue;
      if (a.involves(author.id) || world.knows("ARC", a.id, author.id)) continue;
      if (a.roles.values().stream().noneMatch(involved::contains)) continue;
      out.add("@" + author.handle + " doesn't know about " + names(a) + "'s " + a.defId.replace('_', ' ')
          + "; nothing they write may reveal or hint at it.");
    }
    return out;
  }

  private String names(app.radiance.sim.model.Arc a) {
    return a.roles.values().stream().map(id -> world.people.containsKey(id) ? world.people.get(id).name : id)
        .collect(Collectors.joining(" and "));
  }

  private String fill(String template, String today, String people, String canon, String thread, String briefs) {
    return lib.prompt(template).replace("{{today}}", today).replace("{{people}}", people).replace("{{canon}}", canon)
        .replace("{{thread}}", thread).replace("{{briefs}}", briefs);
  }

  /** Keeps the packet under budget by trimming memories, then the oldest thread lines. */
  private static String trim(String content, PromptBudget budget) {
    if (budget == null || content.length() <= budget.maxChars()) return content;
    var lines = new ArrayList<>(List.of(content.split("\n")));
    while (String.join("\n", lines).length() > budget.maxChars()) {
      int i = -1;
      for (int k = lines.size() - 1; k >= 0; k--) {
        if (lines.get(k).startsWith("- ")) {
          i = k;
          break;
        }
      }
      if (i < 0) break;
      lines.remove(i);
    }
    return String.join("\n", lines);
  }

  public String lengthBand(String actorId) {
    return voice(actorId).path("lengthBand").asString("SHORT");
  }

  public long timeOf(Target t) {
    return t.time();
  }

  String handle(String id) {
    var p = world.people.get(id);
    return p == null ? id : "@" + p.handle;
  }

  private static double round(double v) {
    return Math.round(v * 100) / 100.0;
  }

}
