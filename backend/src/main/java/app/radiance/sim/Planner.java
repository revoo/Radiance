package app.radiance.sim;

import app.radiance.content.ContentLibrary;
import app.radiance.content.Defs.EventDef;
import app.radiance.content.Selector;
import app.radiance.content.Vocab;
import app.radiance.content.Vocab.Category;
import app.radiance.content.Vocab.PostOutput;
import app.radiance.content.Vocab.Stance;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Visibility;
import app.radiance.news.Headline;
import app.radiance.sim.model.Drift;
import app.radiance.sim.model.Edge;
import app.radiance.sim.model.Intent;
import app.radiance.sim.model.Item;
import app.radiance.sim.model.Person;
import app.radiance.sim.model.SimWorld;
import app.radiance.social.PostRow;
import app.radiance.social.ReactionRow;
import app.radiance.story.EventRow;
import app.radiance.world.Calendar;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plans one slice (SPEC §5.1, steps 1–5): time-driven updates, the arc engine, the storyteller's
 * incidents, news takes, due intents, and every online resident's browsing — engagement, stances,
 * reactions, comments and replies. Pure: the same world, slice and inputs give the same script.
 * Keeps per-run caches (sessions, planned attention) across the slices of one script.
 */
final class Planner {

  static final long MIN = 60_000L;
  static final long HOUR = 3_600_000L;
  static final long DAY = 86_400_000L;
  private static final int MAX_DEBATE_ROUNDS = 4;

  private final ContentLibrary lib;
  private final Knobs k;
  private final Map<String, List<Presence.Session>> sessions = new HashMap<>();
  /** {@code actor|day} → interactions this run planned (airing counts them for real). */
  private final Map<String, Integer> planned = new HashMap<>();
  /** {@code actor|day} → unprompted engagements with R this run planned. */
  private final Map<String, Integer> userShare = new HashMap<>();
  /** A guard rail under the feel band: nobody posts more than six times in a world-day. */
  static final int MAX_POSTS_PER_DAY = 6;
  /** The storyteller and news stop one short, leaving room for an arc's post the same day. */
  static final int STORYTELLER_POSTS_PER_DAY = MAX_POSTS_PER_DAY - 1;
  /** Intents that fired in this run, so the writer can mark stored ones DONE. */
  final List<Intent> fired = new ArrayList<>();

  Planner(ContentLibrary lib, Knobs k) {
    this.lib = lib;
    this.k = k;
  }

  Script plan(SimWorld w, Slice slice, Plan.Inputs in) {
    var p = new Plan(w, lib, k, slice, in, userShare);
    for (var r : w.residents()) Drift.advance(r, w.seed, k.moodHalfLifeHours(), slice.start());
    w.settleTension(slice.start(), k.tensionHalfLifeDays());
    ArcEngine.plan(p);
    if (!p.structureOnly()) {
      incidents(p);
      news(p);
      intents(p);
      browse(p);
    }
    return p.script;
  }

  // ------------------------------------------------------------------------------------- storyteller

  private void incidents(Plan p) {
    var w = p.w;
    long t0 = p.slice.start();
    var date = w.date(t0);
    long dayStart = date.atStartOfDay(w.zone).toInstant().toEpochMilli();
    var triggers = Calendar.triggers(date);
    var defs = new TreeMap<>(lib.events());
    for (var a : w.residents()) {
      var curve = Presence.curve(a, date, p.in.primeHours(), k.primeTimeBoost());
      double share = Presence.share(curve, dayStart, t0, p.slice.end());
      if (share <= 0) continue;
      // a fresh order per resident and slice, so the daily post cap doesn't favor defs early in the alphabet
      var order = new ArrayList<>(defs.values());
      var shuffle = p.rng.derive("def-order:" + a.id);
      for (int i = order.size() - 1; i > 0; i--) java.util.Collections.swap(order, i, shuffle.nextInt(i + 1));
      for (var def : order) {
        if (def.trigger() != null && def.trigger().headline() != null) continue;
        if (def.category() == Category.DM_USER && w.userId == null) continue;
        if (!fidelityAllows(p, def)) continue;
        if (!calendarAllows(def, a, date, triggers, w)) continue;
        if (!Eligibility.holds(def, a, w, lib, p.in.weather(), t0)) continue;
        if (onCooldown(w, a, def, t0) || overCap(p, def, a, t0)) continue;
        if (posts(def) && postsOn(p, a, t0) >= STORYTELLER_POSTS_PER_DAY) continue;
        double lambda = rate(p, def, a, date) * share;
        var rng = p.rng.derive("incident:" + def.id() + ":" + a.id);
        if (!rng.chance(1 - Math.exp(-lambda))) continue;
        var participants = participants(p, def, a, date, rng);
        if (participants == null) continue;
        Long t = Presence.momentIn(curve, dayStart, t0, p.slice.end(), rng);
        if (t == null) continue;
        fire(p, def, a, participants, t);
      }
    }
  }

  double rate(Plan p, EventDef def, Person a, LocalDate date) {
    double x = def.baseRate() * Rates.modifiers(def, a, date, p.w.tension);
    if (def.category() == Category.DM_USER) return x * k.dmInitiationRate();
    x *= p.in.scales().getOrDefault(def.category().name(), 1.0) * p.in.scales().getOrDefault("actor:" + a.id, 1.0);
    if (def.category() == Category.LIFE || def.category() == Category.DRAMA) {
      x *= Storyteller.factor(k.profile(), p.w.seed, p.slice.start(), p.w.tension, k.dramaIntensity());
    }
    return x;
  }

  /** Posts {@code a} already has on {@code t}'s world-day: aired or planned, this slice included. */
  static int postsOn(Plan p, Person a, long t) {
    String day = p.w.day(t);
    int n = 0;
    for (var i : p.w.items.values()) {
      if (i.type == Item.Type.POST && i.authorId.equals(a.id) && i.status != app.radiance.kernel.AirStatus.DROPPED
          && p.w.day(i.time).equals(day)) n++;
    }
    for (var post : p.script.posts) if (post.authorId().equals(a.id) && p.w.day(post.createdAt()).equals(day)) n++;
    return n;
  }

  private static boolean posts(EventDef def) {
    return def.output() != null && def.output().post() != PostOutput.NONE && def.output().post() != PostOutput.DM;
  }

  private static boolean fidelityAllows(Plan p, EventDef def) {
    var f = p.slice.fidelity();
    if (f == Fidelity.FULL) return true;
    if (def.category() == Category.DM_USER) return false;
    if (f == Fidelity.REDUCED) return def.importance() >= 4 || def.category() == Category.DRAMA;
    return def.category() == Category.LIFE && def.importance() >= 5;
  }

  static boolean calendarAllows(EventDef def, Person a, LocalDate date, java.util.Set<String> triggers, SimWorld w) {
    var when = def.when();
    if (when == null) return true;
    if (!when.seasons().isEmpty() && !when.seasons().contains(Calendar.season(date))) return false;
    if (!when.calendar().isEmpty()) {
      boolean any = false;
      for (String c : when.calendar()) {
        if (c.equals("birthday")) {
          any |= def.participants().isEmpty() ? Calendar.isBirthday(a.birthDate, date)
              : w.residents().stream().anyMatch(x -> x != a && Calendar.isBirthday(x.birthDate, date));
        } else {
          any |= triggers.contains(c);
        }
      }
      return any;
    }
    return true;
  }

  private static boolean onCooldown(SimWorld w, Person a, EventDef def, long t) {
    var times = w.fired.get(a.id + "|" + def.id());
    if (times == null || times.isEmpty()) return false;
    double cd = def.cooldownDays() != null ? def.cooldownDays() : def.when() != null ? 0.9 : 0;
    return cd > 0 && t - times.last() < cd * DAY;
  }

  private boolean overCap(Plan p, EventDef def, Person a, long t) {
    var w = p.w;
    if (def.category() == Category.DRAMA) {
      String day = w.day(t);
      int today = w.dramaByDay.getOrDefault(day, 0);
      for (var e : p.script.events) {
        var d = e.defId() == null || e.arcId() != null ? null : lib.events().get(e.defId());
        if (d != null && d.category() == Category.DRAMA && w.day(e.worldTime()).equals(day)) today++;
      }
      if (today >= k.maxDramaPerDay()) return true;
    }
    if (def.importance() >= 6) {
      var majors = w.majorEvents.get(a.id);
      return majors != null && majors.tailSet(t - 14 * DAY).size() >= k.maxMajorEventsPer14d();
    }
    return false;
  }

  private Map<String, String> participants(Plan p, EventDef def, Person a, LocalDate date, RandomSource rng) {
    var out = new LinkedHashMap<String, String>();
    out.put("actor", a.id);
    for (var e : new TreeMap<>(def.participants()).entrySet()) {
      var sel = Selector.parse(e.getValue());
      boolean birthday = def.when() != null && def.when().calendar().contains("birthday");
      var who = Selectors.pick(p.w, a, sel, c -> !birthday || Calendar.isBirthday(c.birthDate, date), rng);
      if (who == null) return null;
      out.put(e.getKey(), who.id);
    }
    return out;
  }

  /** One storyteller incident: its event, and the post or DM that shows it. */
  private void fire(Plan p, EventDef def, Person a, Map<String, String> participants, long t) {
    var w = p.w;
    var out = def.output() == null ? PostOutput.NONE : def.output().post();
    String other = participants.entrySet().stream().filter(e -> !e.getKey().equals("actor")).map(Map.Entry::getValue)
        .findFirst().orElse(null);
    var learned = new ArrayList<List<String>>();
    if (def.effects().containsKey("knowledge_spread") && other != null) gossip(p, a, other, learned);
    String said = def.description() != null ? def.description()
        : def.output() != null && def.output().brief() != null ? def.output().brief() : def.id();
    for (var e : participants.entrySet()) {
      if (!e.getKey().equals("actor")) said = said.replace("@" + e.getKey(), "@" + w.person(e.getValue()).handle);
    }
    var payload = new EventPayload(def.category().name(), participants, def.effects(), null, null, learned,
        def.when() == null ? null : def.when().weather(), said);
    var vis = out == PostOutput.NONE || out == PostOutput.DM ? Visibility.PRIVATE : Visibility.PUBLIC;
    var source = def.when() != null && !def.when().calendar().isEmpty() ? EventRow.Source.CALENDAR : EventRow.Source.STORYTELLER;
    var ev = p.event("incident:" + def.id() + ":" + a.id + ":" + t, t, def.id().toUpperCase(), def.id(), a.id, other, null,
        def.importance(), vis, source, payload);
    if (out == PostOutput.NONE) return;
    String text = def.output().brief();
    for (var e : participants.entrySet()) {
      if (!e.getKey().equals("actor")) text = text.replace("@" + e.getKey(), "@" + w.person(e.getValue()).handle);
    }
    var mentions = participants.entrySet().stream().filter(e -> !e.getKey().equals("actor")).map(Map.Entry::getValue).toList();
    var brief = new Brief(def.id(), def.category().name(), text, null, out == PostOutput.DM ? other : null,
        out == PostOutput.DM ? dmInteraction(def) : null, topics(def, a), def.category() == Category.DRAMA ? 0.5 : 0.15,
        def.category() == Category.DRAMA ? 0.8 : 0.1,
        Plan.extra("valence", valence(def, a), "mentions", mentions.isEmpty() ? null : mentions,
            "media", def.output().media(), "participants", participants));
    if (out == PostOutput.DM) {
      if (other != null) p.dm("incident:" + def.id() + ":" + a.id + ":" + t, t + MIN, a.id, other, brief);
      return;
    }
    p.post("incident:" + def.id() + ":" + a.id + ":" + t, t + MIN, a.id, PostRow.Kind.valueOf(out.name()), brief, null,
        ev.id(), null, def.importance());
  }

  private static String dmInteraction(EventDef def) {
    if (def.id().contains("crush")) return "dm_flirt";
    if (def.id().contains("complaint")) return "dm_curt";
    return "dm_warm";
  }

  /** A GOSSIP event spreads a private thing the actor knows to a trusted friend (SPEC §5.6). */
  private static void gossip(Plan p, Person a, String to, List<List<String>> learned) {
    for (var arc : p.w.arcs.values()) {
      if (!arc.active() || arc.visibility == Visibility.PUBLIC || arc.involves(to)) continue;
      if (p.w.knows("ARC", arc.id, a.id) && !p.w.knows("ARC", arc.id, to) && !arc.involves(a.id)) {
        learned.add(List.of("ARC", arc.id, to));
        return;
      }
    }
  }

  static List<String> topics(EventDef def, Person a) {
    var out = new ArrayList<String>();
    for (String t : def.tags()) if (Vocab.TOPICS.contains(t)) out.add(t);
    if (def.tags().contains("sports")) {
      a.quirks.stream().filter(q -> q.startsWith("sports_diehard:")).findFirst()
          .ifPresent(q -> out.add("sports:" + q.substring(15)));
      if (out.stream().noneMatch(x -> x.startsWith("sports"))) out.add("sports");
    }
    if (def.tags().contains("physical")) out.add("fitness");
    return out;
  }

  static double valence(EventDef def, Person a) {
    String id = def.id();
    if (id.contains("gripe") || id.contains("complain") || id.contains("vent") || id.contains("rant")) return -0.4;
    return switch (def.category()) {
      case DRAMA -> -0.4;
      case LIFE -> 0.6;
      case FAMILY, SOCIAL -> 0.4;
      default -> id.equals("mood_post") ? Math.signum(a.mood) * 0.4 : 0.1;
    };
  }

  // ------------------------------------------------------------------------------------- news

  private void news(Plan p) {
    var w = p.w;
    long t0 = p.slice.start();
    var date = w.date(t0);
    long dayStart = date.atStartOfDay(w.zone).toInstant().toEpochMilli();
    // takes draw on the batch's headlines of the last 36 hours, all day long (scripts trail the news)
    long newest = p.in.headlines().stream().mapToLong(Headline::publishedAt).max().orElse(0);
    var fresh = p.in.headlines().stream().filter(h -> h.publishedAt() <= t0 && h.publishedAt() > newest - 36 * HOUR).toList();
    if (fresh.isEmpty()) return;
    for (var a : w.residents()) {
      var curve = Presence.curve(a, date, p.in.primeHours(), k.primeTimeBoost());
      double share = Presence.share(curve, dayStart, t0, p.slice.end());
      if (share <= 0) continue;
      for (var def : new TreeMap<>(lib.events()).values()) {
        if (def.trigger() == null || def.trigger().headline() == null) continue;
        if (p.slice.fidelity() != Fidelity.FULL && def.importance() < 4) continue;
        if (!Eligibility.holds(def, a, w, lib, p.in.weather(), t0)) continue;
        double min = def.trigger().headline().minInterest();
        var rng = p.rng.derive("news:" + def.id() + ":" + a.id);
        var interesting = fresh.stream().filter(h -> interest(a, h) >= min && !took(w, a, h)).toList();
        if (interesting.isEmpty()) continue;
        String region = region(p, rng);
        var eligible = interesting.stream().filter(h -> h.region().equals(region)).toList();
        if (postsOn(p, a, t0) >= STORYTELLER_POSTS_PER_DAY) continue;
        // the region is drawn first, so takes follow region-mix; a region with nothing for them is a pass
        double lambda = rate(p, def, a, date) * interesting.size() / 24.0 * share;
        if (!rng.chance(1 - Math.exp(-lambda)) || eligible.isEmpty()) continue;
        var h = pickHeadline(eligible, a, rng);
        Long t = Presence.momentIn(curve, dayStart, t0, p.slice.end(), rng);
        if (t == null) continue;
        take(p, def, a, h, t);
        w.fired.computeIfAbsent(a.id + "|news:" + h.id(), x -> new java.util.TreeSet<>()).add(t);
      }
    }
  }

  /** Interest = topic match × salience × (1 + politics_interest × controversy) (SPEC §5.4). */
  static double interest(Person a, Headline h) {
    double match = 0;
    for (String t : h.topics()) match = Math.max(match, a.interest(t));
    if (h.topics().contains("politics")) match = Math.max(match, a.politics.interest());
    return match * h.salience() * (1 + a.politics.interest() * h.controversy());
  }

  private static boolean took(SimWorld w, Person a, Headline h) {
    return w.fired.containsKey(a.id + "|news:" + h.id());
  }

  /**
   * A take's region, drawn before its headline (SPEC §5.12): {@code news.region-mix}, leaning toward
   * whichever region the last few days' takes have shorted, so the split holds over a month.
   */
  private String region(Plan p, RandomSource rng) {
    var mix = new TreeMap<>(k.regionMix());
    var byId = new HashMap<String, String>();
    p.in.headlines().forEach(h -> byId.put(h.id(), h.region()));
    var seen = new TreeMap<String, Integer>();
    int total = 0;
    for (var i : p.w.items.values()) {
      String r = i.headlineId == null ? null : byId.get(i.headlineId);
      if (r != null) {
        seen.merge(r, 1, Integer::sum);
        total++;
      }
    }
    for (var post : p.script.posts) {
      String r = post.headlineId() == null ? null : byId.get(post.headlineId());
      if (r != null) {
        seen.merge(r, 1, Integer::sum);
        total++;
      }
    }
    double sum = 0;
    var weights = new TreeMap<String, Double>();
    for (var e : mix.entrySet()) {
      double share = total == 0 ? e.getValue() : seen.getOrDefault(e.getKey(), 0) / (double) total;
      double wgt = e.getValue() * Math.max(0.1, 1 + 4 * (e.getValue() - share));
      weights.put(e.getKey(), wgt);
      sum += wgt;
    }
    double u = rng.nextDouble() * sum;
    for (var e : weights.entrySet()) {
      u -= e.getValue();
      if (u < 0) return e.getKey();
    }
    return mix.lastKey();
  }

  /** The headline within the drawn region, by interest. */
  private Headline pickHeadline(List<Headline> list, Person a, RandomSource rng) {
    double sum = list.stream().mapToDouble(h -> interest(a, h)).sum();
    double u = rng.nextDouble() * sum;
    for (var h : list) {
      u -= interest(a, h);
      if (u < 0) return h;
    }
    return list.getLast();
  }

  /** A LINK take; stance is computed, not generated: resident axes · headline signal, plus noise. */
  private void take(Plan p, EventDef def, Person a, Headline h, long t) {
    double signal = (h.axisSignal().getOrDefault("economic", 0.0) + h.axisSignal().getOrDefault("social", 0.0)) / 2;
    double mine = (a.politics.economic() + a.politics.social()) / 2;
    var rng = p.rng.derive("take:" + a.id + ":" + h.id());
    double cheer = Math.signum(mine * signal + rng.nextGaussian(0, 0.1));
    double lean = Math.max(-1, Math.min(1, mine * (0.5 + h.controversy())));
    var payload = new EventPayload(Category.NEWS.name(), Map.of("actor", a.id), def.effects(), null, null, List.of(), null,
        h.title());
    var ev = p.event("news:" + def.id() + ":" + a.id + ":" + h.id(), t, def.id().toUpperCase(), def.id(), a.id, null, null,
        def.importance(), Visibility.PUBLIC, EventRow.Source.NEWS, payload);
    var brief = new Brief(def.id(), Category.NEWS.name(), def.output().brief(), cheer >= 0 ? "FOR" : "AGAINST", null,
        null, h.topics(), h.controversy(), h.controversy() * 0.6,
        Plan.extra("headline", h.title(), "region", h.region(), "lean", lean, "tragic", h.tragic() ? true : null,
            "valence", h.tragic() ? -0.9 : 0.0));
    p.post("news:" + def.id() + ":" + a.id + ":" + h.id(), t + MIN, a.id, PostRow.Kind.LINK, brief, null, ev.id(), h.id(),
        def.importance());
  }

  // ------------------------------------------------------------------------------------- browsing

  List<Presence.Session> sessionsOf(Plan p, Person a, LocalDate d) {
    return sessions.computeIfAbsent(a.id + "|" + d, x -> Presence.sessions(a, d, p.w.zone, p.w.seed, lib.engagement(),
        p.in.primeHours(), k.primeTimeBoost()));
  }

  /** The first session of {@code a} starting after {@code t}, up to two days out. */
  Long nextSession(Plan p, Person a, long t) {
    var d = p.w.date(t);
    for (int i = 0; i < 3; i++) {
      for (var s : sessionsOf(p, a, d.plusDays(i))) if (s.start() > t) return s.start();
    }
    return null;
  }

  int attentionUsed(Plan p, Person a, String day) {
    return a.interactionsOn(day) + planned.getOrDefault(a.id + "|" + day, 0);
  }

  void spend(Person a, String day) {
    planned.merge(a.id + "|" + day, 1, Integer::sum);
  }

  private void browse(Plan p) {
    var w = p.w;
    var date = w.date(p.slice.start());
    for (var b : w.residents()) {
      for (var s : sessionsOf(p, b, date)) {
        if (s.start() < p.slice.start() || s.start() >= p.slice.end()) continue;
        session(p, b, s);
      }
    }
  }

  private void session(Plan p, Person b, Presence.Session s) {
    var w = p.w;
    String day = w.day(s.start());
    long since = b.lastFeedCheck != null ? Math.max(b.lastFeedCheck, s.start() - 2 * DAY) : s.start() - DAY;
    var feed = new ArrayList<Item>();
    for (var i : w.items.values()) {
      if (i.type != Item.Type.POST || i.time <= since || i.time > s.start() || i.authorId.equals(b.id)) continue;
      if (i.status == app.radiance.kernel.AirStatus.DROPPED || i.reactors.contains(b.id) || i.commenters.contains(b.id)) continue;
      var e = w.edgeOrNull(b.id, i.authorId);
      if (!i.publicAudience && (e == null || !e.connected)) continue;
      feed.add(i);
    }
    feed.sort((x, y) -> Double.compare(rank(w, b, y, s.start()), rank(w, b, x, s.start())));
    var rng = p.rng.derive("session:" + b.id + ":" + s.start());
    int n = 0;
    for (var item : feed) {
      if (n++ >= 25 || attentionUsed(p, b, day) >= b.routine.attentionBudget()) break;
      engage(p, b, item, s, day, rng);
    }
    b.lastFeedCheck = s.start();
    b.dirtyState = true;
  }

  private static double rank(SimWorld w, Person b, Item i, long now) {
    var e = w.edgeOrNull(b.id, i.authorId);
    double closeness = e == null ? 0.1 : 0.2 + (Math.abs(e.affinity) + e.familiarity) / 200;
    return closeness * Math.exp(-(now - i.time) / (12.0 * HOUR));
  }

  /** The engagement utility (SPEC §5.8). */
  double utility(SimWorld w, Person b, Item i, Person a, String day, RandomSource rng) {
    var r = lib.engagement();
    var e = w.edgeOrNull(b.id, a.id);
    double aff = e == null ? 0 : e.affinity, fam = e == null ? 0 : e.familiarity;
    double topic = 0;
    for (String t : i.topics) topic = Math.max(topic, b.interest(t));
    var back = w.edgeOrNull(a.id, b.id);
    double recip = back != null && back.lastInteraction != null && w.time - back.lastInteraction < 7 * DAY ? 1 : 0;
    double sat = Math.min(1, i.commentCount / 8.0);
    if (i.drama >= 0.5) sat = -sat;
    double inner = r.wRel() * Math.abs(aff) / 100 + r.wFam() * fam / 100 + r.wTopic() * topic
        + r.wCtrv() * b.trait("argumentativeness") / 100 * i.controversy + r.wDrama() * b.trait("nosiness") / 100 * i.drama
        + r.wRecip() * recip + r.wMood() * b.mood;
    double s = a.user ? k.userSalience() : 1;
    double kind = k.contentKindWeights().getOrDefault(kindKey(i), 1.0);
    double fatigue = (double) (b.interactionsOn(day) + planned.getOrDefault(b.id + "|" + day, 0))
        / Math.max(1, b.routine.attentionBudget());
    return s * kind * inner - r.wFat() * fatigue - r.wSat() * sat + rng.nextGaussian(0, r.noiseSd());
  }

  /**
   * Past about a third of the cast, an ordinary thread stops drawing new commenters; only drama
   * keeps a pile-on going (SPEC §5.8: saturation inverts on drama items).
   */
  static boolean crowded(SimWorld w, Item i, int extra) {
    if (i.drama >= 0.5) return false;
    long distinct = i.commenters.stream().distinct().count() + extra;
    return distinct >= Math.floor(0.37 * w.residents().size());
  }

  static String kindKey(Item i) {
    return switch (i.kind) {
      case "PHOTO" -> "PHOTO";
      case "LINK" -> "LINK";
      case "COMMENT" -> "COMMENT";
      case "REPLY" -> "REPLY";
      default -> "TEXT";
    };
  }

  static double sigmoid(double x) {
    return 1 / (1 + Math.exp(-x));
  }

  private void engage(Plan p, Person b, Item item, Presence.Session s, String day, RandomSource rng) {
    var w = p.w;
    var a = w.person(item.authorId);
    if (a.user && !userShareOk(w, b, a, day)) return;
    var r = lib.engagement();
    double u = utility(w, b, item, a, day, rng);
    boolean comment = !crowded(w, item, 0) && rng.chance(sigmoid(r.steepness() * (u - r.thetaComment())));
    boolean react = comment ? rng.chance(0.5) : rng.chance(sigmoid(r.steepness() * (u - r.thetaReact())));
    if (!comment && !react) return;
    long t = s.start() + 30_000L + (long) (rng.nextDouble() * Math.max(MIN, s.end() - s.start()));
    var e = w.edgeOrNull(b.id, a.id);
    double aff = e == null ? 0 : e.affinity;
    Stance stance = comment ? Stances.comment(w, b, a, item, lib, k, day, rng) : null;
    if (react) {
      p.reaction(t, ReactionRow.Target.POST, item.id, b.id, Stances.reaction(stance, b, item, aff, rng));
      spend(b, day);
    }
    if (comment && attentionUsed(p, b, day) < b.routine.attentionBudget()) {
      var c = p.comment("browse:" + b.id + ":" + item.id, t + 20_000L, item.id, null, b.id,
          commentBrief(item, stance, a.id, null));
      spend(b, day);
      if (!a.user) replyIntent(p, a, b, c.id(), item.id, stance, c.createdAt(), 1);
    }
    if (a.user) userShare.merge(b.id + "|" + day, 1, Integer::sum);
  }

  /** Unprompted engagement with R stays under {@code max-user-share-of-attention} (SPEC §5.8). */
  private boolean userShareOk(SimWorld w, Person b, Person user, String day) {
    var e = w.edgeOrNull(b.id, user.id);
    if (e != null && (e.kind == app.radiance.graph.RelationshipRow.Kind.CLOSE_FRIEND
        || e.kind == app.radiance.graph.RelationshipRow.Kind.PARTNER)) return true;
    int withUser = userShare.getOrDefault(b.id + "|" + day, 0);
    return withUser < Math.max(1, k.maxUserShareOfAttention() * b.routine.attentionBudget());
  }

  static Brief commentBrief(Item on, Stance stance, String toward, String interaction) {
    String key = interaction != null ? interaction : Airing.interactionFor(stance.name());
    return new Brief(on.defId, on.category, null, stance.name(), toward, key, on.topics, on.controversy, on.drama,
        Plan.extra("on", on.id));
  }

  /** The person addressed may answer at their next session; debates alternate until someone tires. */
  void replyIntent(Plan p, Person to, Person from, String commentId, String postId, Stance stance, long after, int round) {
    if (round > MAX_DEBATE_ROUNDS || to.user) return;
    Long due = nextSession(p, to, after);
    if (due == null) return;
    var payload = new TreeMap<String, Object>();
    payload.put("postId", postId);
    payload.put("from", from.id);
    payload.put("stance", stance.name());
    payload.put("round", round);
    p.w.intents.add(new Intent(p.id("intent", after, "reply:" + commentId + ":" + to.id), due + 2 * MIN, to.id, "REPLY",
        "COMMENT", commentId, payload, null));
  }

  // ------------------------------------------------------------------------------------- intents

  private void intents(Plan p) {
    var w = p.w;
    var due = w.intents.stream().filter(i -> i.dueAt() < p.slice.end())
        .sorted(java.util.Comparator.comparingLong(Intent::dueAt).thenComparing(Intent::id)).toList();
    for (var i : due) {
      w.intents.remove(i);
      fired.add(i);
      if ("REPLY".equals(i.kind())) reply(p, i);
    }
  }

  private void reply(Plan p, Intent i) {
    var w = p.w;
    var x = w.people.get(i.actorId());
    var from = w.people.get(String.valueOf(i.payload().get("from")));
    String postId = String.valueOf(i.payload().get("postId"));
    var post = w.items.get(postId);
    if (x == null || from == null || post == null || post.status == app.radiance.kernel.AirStatus.DROPPED) return;
    var incoming = Stance.valueOf(String.valueOf(i.payload().get("stance")));
    int round = ((Number) i.payload().get("round")).intValue();
    long t = Math.max(i.dueAt(), p.slice.start());
    String day = w.day(t);
    boolean toR = from.user;
    if (!toR && attentionUsed(p, x, day) >= x.routine.attentionBudget()) return;
    var e = w.edgeOrNull(x.id, from.id);
    double aff = e == null ? 0 : e.affinity;
    boolean clash = Airing.CLASH.contains(incoming.name());
    var rng = p.rng.derive("reply:" + i.id());
    double pr = toR ? 0.6 : 0.15 + 0.35 * x.trait("argumentativeness") / 100 * (clash ? 1.6 : 0.6) + 0.25 * Math.abs(aff) / 100
        + (incoming == Stance.QUESTION || incoming == Stance.FLIRT ? 0.3 : 0) - 0.12 * (round - 1);
    if (!rng.chance(Math.max(0, Math.min(0.9, pr)))) {
      if (rng.chance(0.5)) p.reaction(t, ReactionRow.Target.COMMENT, i.targetId(), x.id, Stances.reaction(incoming, x, post, aff, rng));
      return;
    }
    Stance stance;
    if (clash) {
      stance = rng.chance(0.25 + x.trait("humor") / 400) ? Stance.JOKE
          : aff < -30 && rng.chance(0.4 * k.edge()) ? Stance.HOSTILE : Stance.DISAGREE;
    } else if (incoming == Stance.FLIRT) {
      double ladder = Stances.flirt(x, from, e == null ? 0 : e.attraction, e == null ? 0 : e.chemistry, k);
      stance = ladder > -1.5 && rng.chance(sigmoid(ladder + 1)) ? Stance.FLIRT : Stance.JOKE;
    } else {
      stance = Stances.comment(w, x, from, post, lib, k, day, rng);
    }
    String key = clash && Airing.CLASH.contains(stance.name()) ? "debate_exchange" : null;
    var c = p.comment("reply:" + i.id(), t + rng.nextInt(10) * MIN, postId, i.targetId(), x.id,
        commentBrief(post, stance, from.id, key));
    spend(x, day);
    if (Airing.CLASH.contains(stance.name()) || stance == Stance.FLIRT || stance == Stance.QUESTION) {
      replyIntent(p, from, x, c.id(), postId, stance, c.createdAt(), round + 1);
    }
  }

  static Map<String, EnumMap<Category, Double>> rawRates(SimWorld w, ContentLibrary lib, LocalDate from, int days,
      List<Headline> headlines) {
    var out = new TreeMap<String, EnumMap<Category, Double>>();
    // takes draw on the headlines of the last 36 hours, as news() does
    long newest = headlines.stream().mapToLong(Headline::publishedAt).max().orElse(0);
    var fresh = headlines.stream().filter(h -> h.publishedAt() > newest - 36 * HOUR).toList();
    for (var a : w.residents()) {
      var row = new EnumMap<Category, Double>(Category.class);
      for (int d = 0; d < days; d++) {
        var date = from.plusDays(d);
        for (var def : lib.events().values()) {
          if (def.category() == Category.DM_USER || def.when() != null) continue;
          if (!Eligibility.holds(def, a, w, lib, null, w.time)) continue;
          double x = def.baseRate() * Rates.modifiers(def, a, date, 50) / days;
          if (def.trigger() != null && def.trigger().headline() != null) {
            double min = def.trigger().headline().minInterest();
            var interesting = fresh.stream().filter(h -> interest(a, h) >= min).toList();
            // a take's region is drawn first; a region with nothing for them is a pass
            double hit = 0;
            for (var e : Knobs.defaults().regionMix().entrySet()) {
              if (interesting.stream().anyMatch(h -> h.region().equals(e.getKey()))) hit += e.getValue();
            }
            x *= interesting.size() / 24.0 * hit;
          }
          // a cooldown caps what any scale can buy: one per cooldown, at most
          if (def.cooldownDays() != null && def.cooldownDays() > 0) x = Math.min(x, 1.0 / def.cooldownDays());
          row.merge(def.category(), x, Double::sum);
        }
      }
      out.put(a.id, row);
    }
    return out;
  }
}
