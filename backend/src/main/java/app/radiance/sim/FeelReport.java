package app.radiance.sim;

import app.radiance.content.Vocab.Category;
import app.radiance.content.Vocab.Temperament;
import app.radiance.kernel.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The feel report (SPEC §10): what a simulated month felt like, read back from the database, with the
 * bands CI asserts at default knobs. Bands that need the Pacer (first reaction within 6 s, per-minute
 * caps, the quiet gap) belong to M7 and aren't measured here.
 */
public final class FeelReport {

  public record Band(String name, String value, boolean ok) {}

  public final List<Band> bands = new ArrayList<>();
  public final List<String> lines = new ArrayList<>();
  public final Map<String, Integer> userTags = new TreeMap<>();
  public double userPostComments;
  public double userPostReactions;

  record PostLite(String id, String author, long t, String kind, String category, String headline, String arc) {}

  record CommentLite(String id, String post, String parent, String author, long t, String stance, String interaction) {}

  public static FeelReport of(Simulation sim) {
    var r = new FeelReport();
    r.build(sim);
    return r;
  }

  private void build(Simulation s) {
    var sql = s.sql;
    var w = s.engine.world();
    long from = s.start, to = s.end;
    double days = (to - from) / 86_400_000.0;
    String user = s.userId;
    var residents = w.residents();

    var posts = sql.query("SELECT id, author_id, created_at, kind, brief_json, headline_id, arc_id FROM post WHERE"
        + " air_status = 'AIRED' AND created_at >= ? AND created_at < ?", List.of(from, to), x -> new PostLite(
        x.str("id"), x.str("author_id"), x.lng("created_at"), x.str("kind"), Brief.parse(x.str("brief_json")).category(),
        x.str("headline_id"), x.str("arc_id")));
    var comments = sql.query("SELECT id, post_id, parent_id, author_id, created_at, brief_json FROM comment WHERE"
        + " air_status = 'AIRED' AND created_at >= ? AND created_at < ?", List.of(from, to), x -> {
          var b = Brief.parse(x.str("brief_json"));
          return new CommentLite(x.str("id"), x.str("post_id"), x.str("parent_id"), x.str("author_id"),
              x.lng("created_at"), b.stance(), b.interaction());
        });
    var reactionsByTarget = new HashMap<String, Integer>();
    sql.query("SELECT target_id FROM reaction WHERE air_status = 'AIRED' AND created_at >= ? AND created_at < ?",
        List.of(from, to), x -> x.str("target_id")).forEach(id -> reactionsByTarget.merge(id, 1, Integer::sum));
    var commentsByPost = comments.stream().collect(Collectors.groupingBy(CommentLite::post));
    var repliesTo = comments.stream().filter(c -> c.parent() != null).collect(Collectors.groupingBy(CommentLite::parent));

    // ---- posting rates
    var perResident = new TreeMap<String, Integer>();
    var perDay = new HashMap<String, Integer>();
    for (var p : posts) {
      if (p.author().equals(user)) continue;
      perResident.merge(p.author(), 1, Integer::sum);
      perDay.merge(p.author() + "|" + w.day(p.t()), 1, Integer::sum);
    }
    int maxPerDay = perDay.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    long lurkers = residents.stream().filter(p -> perResident.getOrDefault(p.id, 0) / days * 7 < 1).count();
    lines.add("Posts per resident per day:");
    for (var p : residents) {
      lines.add(String.format("  %-22s %5.2f (routine %.2f)", "@" + p.handle, perResident.getOrDefault(p.id, 0) / days,
          p.routine.postsPerDay()));
    }
    bands.add(new Band("no resident posts more than 6 times a day", "max " + maxPerDay, maxPerDay <= 6));
    bands.add(new Band("at least 4 residents post less than once a week", lurkers + " lurkers", lurkers >= 4));

    // ---- comments per post (residents' posts)
    var cpp = new ArrayList<Integer>();
    for (var p : posts) if (!p.author().equals(user)) cpp.add(commentsByPost.getOrDefault(p.id(), List.of()).size());
    lines.add(String.format("Comments per resident post: median %.1f, p90 %.1f, max %d", median(cpp), pct(cpp, 0.9),
        cpp.stream().mapToInt(Integer::intValue).max().orElse(0)));

    // ---- R's content
    var photoR = new ArrayList<Integer>();
    var photoC = new ArrayList<Integer>();
    var textR = new ArrayList<Integer>();
    var textC = new ArrayList<Integer>();
    double maxShare = 0;
    for (var p : posts) {
      if (!p.author().equals(user)) continue;
      var cs = commentsByPost.getOrDefault(p.id(), List.of()).stream().filter(c -> !c.author().equals(user)).toList();
      int reacts = reactionsByTarget.getOrDefault(p.id(), 0);
      (p.kind().equals("PHOTO") ? photoR : textR).add(reacts);
      (p.kind().equals("PHOTO") ? photoC : textC).add(cs.size());
      maxShare = Math.max(maxShare, cs.stream().map(CommentLite::author).distinct().count() / (double) residents.size());
    }
    var cReacts = new ArrayList<Integer>();
    var cAnswers = new ArrayList<Integer>();
    for (String id : s.userComments) {
      cReacts.add(reactionsByTarget.getOrDefault(id, 0));
      cAnswers.add((int) repliesTo.getOrDefault(id, List.of()).stream().filter(c -> !c.author().equals(user)).count());
    }
    userPostComments = mean(photoC, textC);
    userPostReactions = mean(photoR, textR);
    lines.add(String.format("R's photo posts (%d): median %.1f reactions, %.1f comments", photoR.size(), median(photoR),
        median(photoC)));
    lines.add(String.format("R's text posts (%d): median %.1f reactions, %.1f comments", textR.size(), median(textR),
        median(textC)));
    lines.add(String.format("R's comments (%d): median %.1f reactions, %.1f answers", cReacts.size(), median(cReacts),
        median(cAnswers)));
    if (!photoR.isEmpty()) {
      bands.add(new Band("R's median photo post: 10–18 reactions", fmt(median(photoR)), between(median(photoR), 10, 18)));
      bands.add(new Band("R's median photo post: 4–9 comments", fmt(median(photoC)), between(median(photoC), 4, 9)));
    }
    if (!textR.isEmpty()) {
      bands.add(new Band("R's median text post: 6–12 reactions", fmt(median(textR)), between(median(textR), 6, 12)));
      bands.add(new Band("R's median text post: 2–6 comments", fmt(median(textC)), between(median(textC), 2, 6)));
    }
    if (!cReacts.isEmpty()) {
      bands.add(new Band("R's median comment: 0–3 reactions, at most one answer",
          fmt(median(cReacts)) + " / " + fmt(median(cAnswers)), median(cReacts) <= 3 && median(cAnswers) <= 1));
    }
    bands.add(new Band("no post of R's draws comments from more than 40% of the cast", pctFmt(maxShare), maxShare <= 0.4));

    // ---- impressions of R
    for (var e : w.edges.values()) {
      if (!e.to.equals(user)) continue;
      for (String t : e.tags) if (Impressions.TAGS.contains(t)) userTags.merge(t, 1, Integer::sum);
    }
    lines.add("R's impression tags: " + userTags);

    // ---- temperament
    boolean grouchesOk = true;
    var grouchLine = new ArrayList<String>();
    for (var p : residents) {
      if (p.temper.preset() != Temperament.GROUCH) continue;
      // toward the people they know: stranger rows made on the fly sit at the default until they settle
      var meters = w.edgesFrom(p.id).stream().filter(e -> !e.to.equals(user)
          && e.kind != app.radiance.graph.RelationshipRow.Kind.STRANGER).map(e -> e.affinity).sorted().toList();
      double med = medianD(meters);
      grouchLine.add("@" + p.handle + " " + fmt(med));
      grouchesOk &= med < 0;
    }
    bands.add(new Band("each grouch's median meter toward the cast stays below 0", String.join(", ", grouchLine), grouchesOk));
    double sweetMin = Double.POSITIVE_INFINITY, typicalMin = Double.POSITIVE_INFINITY;
    for (var p : residents) {
      var e = w.edgeOrNull(p.id, user);
      if (e == null) continue;
      if (p.temper.preset() == Temperament.SWEETHEART) sweetMin = Math.min(sweetMin, e.affinity);
      if (p.temper.preset() == Temperament.TYPICAL) typicalMin = Math.min(typicalMin, e.affinity);
    }
    bands.add(new Band("no sweetheart's meter for R drops below 0", fmt(sweetMin), sweetMin >= 0));
    bands.add(new Band("typical residents stay above −30 for R without arc events", fmt(typicalMin), typicalMin >= -30.5));

    // ---- dispositions move
    long moved = s.startMeters.entrySet().stream().filter(e -> {
      int i = e.getKey().indexOf('|');
      var now = w.edgeOrNull(e.getKey().substring(0, i), e.getKey().substring(i + 1));
      return now != null && Math.abs(now.affinity - e.getValue()) >= 15;
    }).count();
    bands.add(new Band("at least 10 resident pairs' meters shift by 15+ points", moved + " pairs", moved >= 10));

    // ---- faithfulness
    var attached = residents.stream().filter(p -> p.attached()).sorted((a, b) -> Double.compare(a.trait("faithfulness"),
        b.trait("faithfulness"))).toList();
    var flirts = new HashMap<String, Integer>();
    comments.stream().filter(c -> "FLIRT".equals(c.stance())).forEach(c -> flirts.merge(c.author(), 1, Integer::sum));
    int third = Math.max(1, attached.size() / 3);
    int low = attached.subList(0, third).stream().mapToInt(p -> flirts.getOrDefault(p.id, 0)).sum();
    int high = attached.subList(attached.size() - third, attached.size()).stream().mapToInt(p -> flirts.getOrDefault(p.id, 0)).sum();
    int faithfulFlirts = attached.stream().filter(p -> p.trait("faithfulness") >= 80).mapToInt(p -> flirts.getOrDefault(p.id, 0)).sum();
    long affairs = w.arcs.values().stream().filter(a -> a.defId.equals("affair") && a.startedAt >= from).count();
    bands.add(new Band("least faithful third flirts 3× as often as the most faithful third", low + " vs " + high,
        low >= 3 && low >= 3 * high));
    bands.add(new Band("nobody with faithfulness 80+ flirts back", String.valueOf(faithfulFlirts), faithfulFlirts == 0));
    bands.add(new Band("at most one affair arc starts", String.valueOf(affairs), affairs <= 1));

    // ---- ugliness
    var residentComments = comments.stream().filter(c -> !c.author().equals(user)).toList();
    long ugly = residentComments.stream().filter(c -> Stances.ugly(c.stance())).count();
    double uglyShare = residentComments.isEmpty() ? 0 : ugly / (double) residentComments.size();
    var uglyWeeks = new TreeMap<Long, Integer>();
    var byDay = new TreeMap<String, int[]>();
    for (var c : residentComments) {
      long week = (c.t() - from) / (7 * 86_400_000L);
      uglyWeeks.merge(week, Stances.ugly(c.stance()) ? 1 : 0, Integer::sum);
      var d = byDay.computeIfAbsent(w.day(c.t()), x -> new int[2]);
      d[0]++;
      if (Stances.ugly(c.stance())) d[1]++;
    }
    long fullWeeks = (long) Math.floor(days / 7);
    boolean weekly = true;
    for (long wk = 0; wk < fullWeeks; wk++) weekly &= uglyWeeks.getOrDefault(wk, 0) > 0;
    boolean neverMajority = byDay.values().stream().noneMatch(d -> d[0] >= 4 && d[1] * 2 > d[0]);
    bands.add(new Band("TROLL, MOCK and HOSTILE are 5–12% of comments", pctFmt(uglyShare), between(uglyShare, 0.05, 0.12)));
    bands.add(new Band("ugly comments show up every week, never the majority of a day's", weekly + " / " + neverMajority,
        weekly && neverMajority));

    // ---- storylines and continuity
    var beatDays = new java.util.TreeSet<String>();
    sql.query("SELECT world_time FROM event WHERE air_status = 'AIRED' AND source = 'ARC' AND visibility = 'PUBLIC'"
        + " AND world_time >= ? AND world_time < ?", List.of(from, to), x -> x.lng("world_time")).forEach(t -> beatDays.add(w.day(t)));
    posts.stream().filter(p -> p.arc() != null).forEach(p -> beatDays.add(w.day(p.t())));
    long dayCount = Math.round(days);
    bands.add(new Band("at least one visible storyline beat per world-day", beatDays.size() + " of " + dayCount + " days",
        beatDays.size() >= dayCount));
    int findings = s.days.stream().mapToInt(d -> d.findings().size()).sum();
    bands.add(new Band("zero continuity violations", String.valueOf(findings), findings == 0));
    long aired = s.days.stream().mapToLong(Simulation.Day::aired).sum();
    long dropped = s.days.stream().mapToLong(Simulation.Day::dropped).sum();
    double dropShare = aired + dropped == 0 ? 0 : dropped / (double) (aired + dropped);
    bands.add(new Band("under 5% of scripted items dropped at airing", pctFmt(dropShare), dropShare < 0.05));

    // ---- the mix
    var cats = new TreeMap<String, Integer>();
    for (var p : posts) if (!p.author().equals(user) && p.category() != null) cats.merge(p.category(), 1, Integer::sum);
    int catTotal = cats.values().stream().mapToInt(Integer::intValue).sum();
    var mixLine = new ArrayList<String>();
    boolean mixOk = true;
    for (var c : List.of(Category.DAILY, Category.NEWS, Category.LIFE, Category.HOBBY, Category.FAMILY, Category.SOCIAL,
        Category.DRAMA)) {
      double share = catTotal == 0 ? 0 : cats.getOrDefault(c.name(), 0) / (double) catTotal;
      double want = s.options.knobs().eventMix().getOrDefault(c.name(), 0.0);
      mixLine.add(c.name() + " " + pctFmt(share) + "/" + pctFmt(want));
      mixOk &= Math.abs(share - want) <= 0.05;
    }
    bands.add(new Band("each category's share of posts within 5 points of event-mix", String.join(", ", mixLine), mixOk));
    var regions = new TreeMap<String, Integer>();
    for (var p : posts) {
      if (p.headline() == null) continue;
      sql.queryOne("SELECT region FROM headline WHERE id = ?", List.of(p.headline()), x -> x.str("region"))
          .ifPresent(reg -> regions.merge(reg, 1, Integer::sum));
    }
    int regTotal = regions.values().stream().mapToInt(Integer::intValue).sum();
    boolean regOk = regTotal > 0;
    var regLine = new ArrayList<String>();
    for (var e : s.options.knobs().regionMix().entrySet()) {
      double share = regTotal == 0 ? 0 : regions.getOrDefault(e.getKey(), 0) / (double) regTotal;
      regLine.add(e.getKey() + " " + pctFmt(share) + "/" + pctFmt(e.getValue()));
      regOk &= Math.abs(share - e.getValue()) <= 0.05;
    }
    Collections.sort(regLine);
    bands.add(new Band("news takes split within 5 points of region-mix", String.join(", ", regLine), regOk));

    // ---- volume, drama, cost, rows
    long dramaEvents = sql.query("SELECT def_id FROM event WHERE air_status = 'AIRED' AND world_time >= ? AND world_time < ?",
        List.of(from, to), x -> x.str("def_id")).stream().filter(id -> id != null && s.lib.events().containsKey(id)
        && s.lib.events().get(id).category() == Category.DRAMA).count();
    long debates = commentsByPost.values().stream().filter(cs -> cs.stream()
        .filter(c -> "debate_exchange".equals(c.interaction())).count() >= 2).count();
    long started = w.arcs.values().stream().filter(a -> a.startedAt >= from).count();
    long resolved = w.arcs.values().stream().filter(a -> a.endedAt != null && a.endedAt >= from).count();
    double rowsPerDay = s.days.stream().mapToLong(Simulation.Day::rowsWritten).average().orElse(0);
    long maxRows = s.days.stream().mapToLong(Simulation.Day::rowsWritten).max().orElse(0);
    double renders = (posts.size() + comments.size() * 0.25) / days;
    lines.add(String.format("Arcs started %d, resolved %d; drama events per week %.1f; debate threads %d", started, resolved,
        dramaEvents / days * 7, debates));
    lines.add(String.format("Dropped at airing %d of %d; continuity findings %d", dropped, aired + dropped, findings));
    lines.add(String.format("Estimated render cost if rendered: $%.3f per world-day (≈%.0f thread renders at $0.0008)",
        renders * 0.0008, renders));
    lines.add(String.format("Rows written per world-day (statements + inserted rows): mean %.0f, max %d", rowsPerDay, maxRows));
    bands.add(new Band("a world-day writes at most 20k rows", String.valueOf(maxRows), maxRows <= 20_000));
  }

  public boolean ok() {
    return bands.stream().allMatch(Band::ok);
  }

  public String render(Simulation s) {
    var sb = new StringBuilder();
    sb.append("Feel report — seed ").append(s.options.seed()).append(", ").append(s.options.days()).append(" days, R ")
        .append(s.options.persona().name().toLowerCase()).append("\n\n");
    for (String l : lines) sb.append(l).append('\n');
    sb.append("\nBands (SPEC §10, at default knobs):\n");
    for (var b : bands) sb.append(b.ok() ? "  ✔ " : "  ✘ ").append(b.name()).append(" — ").append(b.value()).append('\n');
    sb.append("\nPlan hashes: ").append(s.days.stream().map(Simulation.Day::planHash).collect(Collectors.joining(" ")))
        .append('\n');
    return sb.toString();
  }

  static double median(List<Integer> xs) {
    return medianD(xs.stream().map(Integer::doubleValue).toList());
  }

  static double medianD(List<Double> xs) {
    if (xs.isEmpty()) return 0;
    var s = xs.stream().sorted().toList();
    int n = s.size();
    return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
  }

  static double pct(List<Integer> xs, double q) {
    if (xs.isEmpty()) return 0;
    var s = xs.stream().sorted().toList();
    return s.get(Math.min(s.size() - 1, (int) Math.floor(q * s.size())));
  }

  static double mean(List<Integer> a, List<Integer> b) {
    var all = new ArrayList<Integer>(a);
    all.addAll(b);
    return all.stream().mapToInt(Integer::intValue).average().orElse(0);
  }

  static boolean between(double v, double lo, double hi) {
    return v >= lo && v <= hi;
  }

  static String fmt(double v) {
    return String.format("%.1f", v);
  }

  static String pctFmt(double v) {
    return String.format("%.0f%%", v * 100);
  }

  static String json(Object o) {
    return Json.write(o);
  }
}
