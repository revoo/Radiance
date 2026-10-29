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
      briefs.add(commentBrief(c, b));
      targets.put(c.id(), new Target(c.parentId() == null ? "COMMENT" : "REPLY", c.id(), c.authorId(), b.stance(), null,
          false, c.createdAt()));
      authors.add(c.authorId());
      if (b.toward() != null) authors.add(b.toward());
    }
    var threadText = threadSoFar(post, comments);
    String content = fill("render-thread", today(t, post.headlineId(), authors), people(authors, t, budget),
        canon(authors, t), threadText, briefsJson(briefs, targets, authors));
    content = trim(content, budget);
    return new Packet(prefix(), content, briefs, targets);
  }

  /** RENDER_DM: the scripted messages in one thread, with the conversation so far. */
  public Packet dms(String threadId, PromptBudget budget) {
    var dms = new DmRepository(sql).messages(threadId);
    var thread = new DmRepository(sql).threads().stream().filter(x -> x.id().equals(threadId)).findFirst().orElseThrow();
    var authors = new LinkedHashSet<String>(List.of(thread.aId(), thread.bId()));
    var briefs = new ArrayList<Brief>();
    var targets = new LinkedHashMap<String, Target>();
    var convo = new StringBuilder();
    long t = dms.isEmpty() ? world.time : dms.getFirst().createdAt();
    for (var m : dms) {
      if (m.body() != null) {
        convo.append(handle(m.senderId())).append(": ").append(m.body()).append('\n');
      } else if (!m.airStatus().name().equals("DROPPED")) {
        var b = Brief.parse(m.briefJson());
        briefs.add(new Brief(b.defId(), b.category(), b.text(), b.stance(), b.toward(), b.interaction(), b.topics(),
            b.controversy(), b.drama(), extraWith(b.extra(), "briefId", m.id(), "kind", "DM", "author", handle(m.senderId()))));
        targets.put(m.id(), new Target("DM", m.id(), m.senderId(), b.stance(), null, false, m.createdAt()));
      }
    }
    String content = fill("render-dm", today(t, null, authors), people(authors, t, budget), canon(authors, t),
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

  private Brief commentBrief(CommentRow c, Brief b) {
    return new Brief(b.defId(), b.category(), b.text(), b.stance(), b.toward(), b.interaction(), b.topics(),
        b.controversy(), b.drama(), extraWith(b.extra(), "briefId", c.id(), "kind", c.parentId() == null ? "COMMENT" : "REPLY",
            "author", handle(c.authorId()), "replyTo", c.parentId() != null ? c.parentId() : c.postId()));
  }

  private static Map<String, Object> extraWith(Map<String, Object> extra, Object... kv) {
    var m = new LinkedHashMap<String, Object>(extra);
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  // ------------------------------------------------------------------------------------- sections

  String today(long t, String headlineId, Set<String> people) {
    var local = Instant.ofEpochMilli(t).atZone(world.zone);
    var date = local.toLocalDate();
    int h = local.getHour();
    String part = h < 5 ? "late night" : h < 12 ? "morning" : h < 17 ? "afternoon" : h < 21 ? "evening" : "night";
    var sb = new StringBuilder();
    sb.append(date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US)).append(", ").append(date).append(", ")
        .append(part).append(" (").append(String.format("%02d:%02d", h, local.getMinute())).append("), ")
        .append(Calendar.season(date)).append(".\n");
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
    var sofar = todaySoFar(t, people);
    if (!sofar.isEmpty()) sb.append("Today so far:\n").append(sofar);
    return sb.toString().strip();
  }

  /** Gists of earlier items today involving these people (SPEC §5.10: scripted "today so far"). */
  private String todaySoFar(long t, Set<String> people) {
    var date = world.date(t);
    long from = date.atStartOfDay(world.zone).toInstant().toEpochMilli();
    var sb = new StringBuilder();
    var rows = sql.query("SELECT author_id, created_at, gist FROM comment WHERE created_at >= ? AND created_at < ?"
        + " AND gist IS NOT NULL AND air_status <> 'DROPPED' ORDER BY created_at LIMIT 200", List.of(from, t),
        r -> new String[] {r.str("author_id"), r.str("gist")});
    int n = 0;
    for (var r : rows) {
      if (!people.contains(r[0])) continue;
      sb.append("- ").append(r[1]).append('\n');
      if (++n >= 8) break;
    }
    return sb.toString();
  }

  String people(Set<String> ids, long t, PromptBudget budget) {
    var sb = new StringBuilder();
    for (String id : ids) {
      var p = world.people.get(id);
      if (p == null) continue;
      sb.append(person(p, ids, t)).append('\n');
    }
    return sb.toString().strip();
  }

  private String person(Person p, Set<String> involved, long t) {
    var sb = new StringBuilder("## @").append(p.handle).append(" — ").append(p.name);
    if (p.user) {
      sb.append(" (R, the newest member; never write as him)\n");
      return sb.toString();
    }
    sb.append('\n');
    var v = voice(p.id);
    sb.append("Voice: ").append(v.path("lengthBand").asString("").toLowerCase()).append(" posts; emoji ")
        .append(v.path("emojiLevel").asString("").toLowerCase()).append("; casing ").append(v.path("casing").asString("").toLowerCase())
        .append("; ").append(v.path("punctuation").asString("")).append("; typos ")
        .append(Math.round(v.path("typoRate").asDouble(0) * 100)).append("%.\n");
    list(sb, "Slang", v.path("slang"));
    list(sb, "Catchphrases", v.path("catchphrases"));
    list(sb, "Talks about", v.path("signatureTopics"));
    list(sb, "Never", v.path("neverDoes"));
    list(sb, "Sample posts", v.path("samplePosts"));
    list(sb, "Sample comments", v.path("sampleComments"));
    sb.append("Right now: ").append(mood(p)).append(".\n");
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
    var memories = new MemoryRetrieval(sql).top(p.id, t, involved, arcIds, Set.of(), MEMORIES);
    if (!memories.isEmpty()) {
      sb.append("Remembers:\n");
      for (var m : memories) sb.append("- ").append(m.text()).append('\n');
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

  /** Each active tag's prose line (SPEC §5.5). */
  List<String> constraintLines(Person p, long t) {
    var out = new ArrayList<String>();
    for (String tag : world.activeTags(p.id, t, lib)) {
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
    return sql.query("SELECT body FROM (SELECT body, created_at FROM post WHERE author_id = ? AND body IS NOT NULL"
        + " UNION ALL SELECT body, created_at FROM comment WHERE author_id = ? AND body IS NOT NULL)"
        + " ORDER BY created_at DESC LIMIT 5", List.of(id, id), r -> r.str("body")).stream()
        .map(b -> String.join(" ", java.util.Arrays.stream(b.strip().split("\\s+")).limit(3).toList()))
        .map(s -> "“" + s + "”").toList();
  }

  String canon(Set<String> ids, long t) {
    var sb = new StringBuilder();
    var facts = sql.query("SELECT subject_id, statement, status, valid_from FROM fact WHERE status <> 'VOID'"
        + " AND valid_from <= ? ORDER BY valid_from DESC", List.of(t), r -> new String[] {r.str("subject_id"),
        r.str("statement"), r.str("status")});
    var per = new HashMap<String, Integer>();
    for (var f : facts) {
      if (!ids.contains(f[0])) continue;
      if (per.merge(f[0], 1, Integer::sum) > FACTS_PER_SUBJECT) continue;
      var p = world.people.get(f[0]);
      sb.append("- @").append(p == null ? f[0] : p.handle).append(" ").append(f[1])
          .append(FactRow.Status.PENDING.name().equals(f[2]) ? " (earlier today)" : "").append('\n');
    }
    return sb.isEmpty() ? "(nothing beyond the cast directory)" : sb.toString().strip();
  }

  private String threadSoFar(PostRow post, List<CommentRow> comments) {
    var sb = new StringBuilder();
    sb.append(handle(post.authorId())).append(" (").append(post.kind().name().toLowerCase()).append(" post): ")
        .append(post.body() != null ? post.body() : "(being written now; see briefs)").append('\n');
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
      if (x.get("media") != null) m.put("media", x.get("media"));
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
