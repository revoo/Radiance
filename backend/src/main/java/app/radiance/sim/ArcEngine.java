package app.radiance.sim;

import app.radiance.content.Defs.ArcDef;
import app.radiance.content.Defs.Phase;
import app.radiance.content.Vocab.BeatKind;
import app.radiance.content.Vocab.Category;
import app.radiance.content.Vocab.PostOutput;
import app.radiance.content.Vocab.StatusKind;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Visibility;
import app.radiance.sim.model.Arc;
import app.radiance.sim.model.Person;
import app.radiance.sim.model.SimWorld;
import app.radiance.social.PostRow;
import app.radiance.story.EventRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The arc engine (SPEC §5.5): starts, transitions and beats, planned into a slice. An arc's phase is a
 * function of elapsed time plus events, so a slice that spans several transitions emits all of them in
 * order (a skipped three weeks lands in the right phase). Blacklisted arcs never start; an active one
 * whose def is blacklisted mid-world takes its {@code abandon} exit.
 */
final class ArcEngine {

  private ArcEngine() {}

  static final long DAY = 86_400_000L;
  static final List<String> BODY_PARTS = List.of("knee", "ankle", "back", "shoulder", "wrist", "hamstring");
  static final List<String> HOBBIES = List.of("pickleball", "pottery", "rock climbing", "sourdough", "guitar",
      "running", "birdwatching", "chess", "woodworking", "salsa dancing");
  static final List<String> GOALS = List.of("a half marathon", "a sprint triathlon", "a 5K under 25 minutes",
      "a Chicago Marathon charity bib", "a 100-mile bike ride");
  static final List<String> PROGRAMS = List.of("an MBA", "a nursing degree", "a coding bootcamp",
      "a master's in fermentation science", "night classes at a community college");

  /** Where an arc stands while this slice is being planned (transitions chain before they apply). */
  private record Shadow(String phase, long enteredAt, Long next) {}

  static void plan(Plan p) {
    for (var arc : List.copyOf(p.w.arcs.values())) {
      if (!arc.active()) continue;
      var def = p.lib.arcs().get(arc.defId);
      if (def == null) continue;
      if (p.lib.isBlacklisted(def.id())) {
        abandon(p, arc, def);
        continue;
      }
      advance(p, arc, def);
    }
    starts(p);
    if (!p.structureOnly()) beats(p);
  }

  // ------------------------------------------------------------------------------------- transitions

  private static void advance(Plan p, Arc arc, ArcDef def) {
    var s = new Shadow(arc.phase, arc.phaseEnteredAt, arc.nextTransitionAt);
    int guard = 0;
    while (s.next() != null && s.next() < p.slice.end() && guard++ < 20) {
      long t = Math.max(s.next(), p.slice.start());
      var phase = phase(def, s.phase());
      if (phase == null) break;
      String to = next(p, arc, phase, s);
      if (to == null) break;
      s = enter(p, arc, def, s.phase(), to, t, false);
    }
  }

  private static void abandon(Plan p, Arc arc, ArcDef def) {
    if (def.abandon() == null || def.abandon().equals(arc.phase)) return;
    enter(p, arc, def, arc.phase, def.abandon(), p.slice.start(), true);
  }

  private static String next(Plan p, Arc arc, Phase phase, Shadow s) {
    if (phase.next() != null) return phase.next();
    if (phase.transitions().isEmpty()) return null;
    var rng = RandomSource.seeded(p.w.seed).derive("arc:" + arc.id + ":" + s.phase() + ":" + s.enteredAt());
    double u = rng.nextDouble(), total = phase.transitions().stream().mapToDouble(x -> x.p()).sum();
    for (var tr : phase.transitions()) {
      u -= tr.p() / total;
      if (u < 0) return tr.to();
    }
    return phase.transitions().getLast().to();
  }

  /** Plans the move into {@code to} at {@code t}: one event, its post, its effects. */
  private static Shadow enter(Plan p, Arc arc, ArcDef def, String from, String to, long t, boolean abandoned) {
    var phase = phase(def, to);
    var prev = phase(def, from);
    boolean same = from.equals(to);
    var vis = phase.visibility() != null ? phase.visibility() : prev != null && prev.visibility() != null
        ? prev.visibility() : arc.visibility;
    Long next = next(p, arc.id, phase, t);
    String status = phase.terminal() ? (abandoned ? "ABANDONED" : "RESOLVED") : "ACTIVE";
    var data = new LinkedHashMap<String, Object>();
    EventPayload.NewThing thing = null;
    var entry = same ? null : phase.entryEvent();
    if (entry != null && entry.createsEntity() != null) {
      thing = newThing(p, arc, entry.createsEntity().kind().name(), entry.createsEntity().fromTable(), t);
      if (thing != null) data.put("entity", thing.id());
    }
    var roles = new TreeMap<>(arc.roles);
    var participants = new TreeMap<>(arc.roles);
    var subject = p.w.people.get(roles.firstEntry().getValue());
    if (subject != null && subject.partnerId != null && !roles.containsValue(subject.partnerId)) {
      participants.put("partner", subject.partnerId);
    }
    String summary = names(p.w, arc) + ": " + def.id().replace('_', ' ') + " (" + to.replace('_', ' ') + ")";
    var change = new EventPayload.ArcChange(arc.id, def.id(), "TRANSITION", from, to, vis.name(), next, status,
        knowers(p.w, arc, phase), roles, data, summary);
    int importance = entry != null ? entry.importance() : 2;
    String type = entry != null ? entry.type() : same ? "ARC_CONTINUES" : "ARC_TRANSITION";
    var payload = new EventPayload(def.category().name(), participants, entry != null ? entry.effects() : Map.of(), change,
        thing, List.of(), null, def.description() + " — " + to.replace('_', ' '));
    var ev = p.event("arc:" + arc.id + ":" + to + ":" + t, t, type, def.id(), roles.firstEntry().getValue(),
        roles.size() > 1 ? roles.lastEntry().getValue() : null, arc.id, importance, vis, EventRow.Source.ARC, payload);
    if (entry != null && entry.post() != null && entry.post() != PostOutput.NONE && entry.post() != PostOutput.DM
        && vis == Visibility.PUBLIC && postsAllowed(p, importance)) {
      var brief = new Brief(def.id(), def.category().name(), def.description() + " — " + to.replace('_', ' '), null, null,
          null, topics(def), 0.2, def.category() == Category.DRAMA ? 0.7 : 0.2,
          Plan.extra("valence", valence(entry.type()), "arcPhase", to, "media", entry.media(), "roles", roles));
      p.post("arc:" + arc.id + ":" + to + ":" + t, t + 60_000L, roles.firstEntry().getValue(),
          PostRow.Kind.valueOf(entry.post().name()), brief, arc.id, ev.id(), null, importance);
    }
    if (entry != null && entry.effects().get("start_arc") instanceof String child) {
      for (String id : new java.util.LinkedHashSet<>(roles.values())) {
        var other = roles.values().stream().filter(x -> !x.equals(id)).findFirst().orElse(null);
        start(p, child, id, other, t + 120_000L, "after " + arc.id);
      }
    }
    return new Shadow(to, t, "ACTIVE".equals(status) ? next : null);
  }

  private static Long next(Plan p, String arcId, Phase phase, long t) {
    if (phase.terminal() || phase.durationDays().size() != 2) return null;
    var rng = RandomSource.seeded(p.w.seed).derive("arc-duration:" + arcId + ":" + phase.id() + ":" + t);
    int min = phase.durationDays().get(0), max = phase.durationDays().get(1);
    return t + (long) ((min + rng.nextDouble() * (max - min)) * DAY);
  }

  /** Role names, or {@code closest_friend_of:<role>}; a PUBLIC phase needs no knowers. */
  static List<String> knowers(SimWorld w, Arc arc, Phase phase) {
    var out = new ArrayList<String>();
    if (phase.visibility() == Visibility.PUBLIC) return out;
    for (String k : phase.knowers()) {
      if (k.startsWith("closest_friend_of:")) {
        String of = arc.roles.get(k.substring(18));
        String friend = of == null ? null : w.closestFriend(of);
        if (friend != null && !out.contains(friend)) out.add(friend);
      } else if (arc.roles.containsKey(k) && !out.contains(arc.roles.get(k))) {
        out.add(arc.roles.get(k));
      }
    }
    if (out.isEmpty()) out.addAll(arc.roles.values());
    return out;
  }

  // ------------------------------------------------------------------------------------- starts

  private static void starts(Plan p) {
    long t0 = p.slice.start();
    double years = p.slice.hours() / 8760.0;
    for (var def : new TreeMap<>(p.lib.startableArcs()).values()) {
      if (def.start() == null || def.start().baseRatePerYear() <= 0) continue;
      if (def.id().equals("affair") && !p.k.affairs()) continue;
      if (def.sensitive() && !sensitiveAllowed(p)) continue;
      var roleNames = new ArrayList<>(def.roles().keySet());
      double tension = def.category() == Category.LIFE || def.category() == Category.DRAMA
          ? Storyteller.factor(p.k.profile(), p.w.seed, t0, p.w.tension, p.k.dramaIntensity()) : 1;
      double scale = def.id().equals("affair") ? p.k.temptation() : 1;
      for (var a : p.w.residents()) {
        if (!roleOk(p, def, roleNames.getFirst(), a, null) || busy(p, def, a, t0)) continue;
        if (roleNames.size() == 1) {
          double lambda = def.start().baseRatePerYear() * years * quirkMultiplier(p, def, a) * tension * scale;
          if (p.rng.derive("arc-start:" + def.id() + ":" + a.id).chance(lambda) && startOk(p, def, a, null)) {
            start(p, def.id(), a.id, null, t0 + p.rng.derive("arc-start-at:" + def.id() + a.id)
                .nextInt((int) Math.max(1, p.slice.end() - t0)), null);
          }
        } else {
          for (var b : p.w.residents()) {
            if (a == b || !roleOk(p, def, roleNames.get(1), b, a) || busy(p, def, b, t0) || !startOk(p, def, a, b)) continue;
            double lambda = def.start().baseRatePerYear() * years * quirkMultiplier(p, def, a) * tension * scale;
            if (def.id().equals("affair")) lambda *= affairLean(p, a, b);
            if (p.rng.derive("arc-start:" + def.id() + ":" + a.id + ":" + b.id).chance(lambda)) {
              start(p, def.id(), a.id, b.id, t0 + p.rng.derive("arc-start-at:" + def.id() + a.id + b.id)
                  .nextInt((int) Math.max(1, p.slice.end() - t0)), null);
            }
          }
        }
      }
    }
  }

  private static boolean sensitiveAllowed(Plan p) {
    return true; // radiance.content.sensitive-arcs defaults to on; the config hook lands with the batch (M6)
  }

  /** b's own faithfulness and happiness decide whether they go along (A.9). */
  private static double affairLean(Plan p, Person a, Person b) {
    double sat = a.partnerSatisfaction == null ? 0.7 : a.partnerSatisfaction;
    return Math.max(0, (1 - sat) * 1.5) * (b.attached() ? (100 - b.trait("faithfulness")) / 100 : 1);
  }

  private static double quirkMultiplier(Plan p, ArcDef def, Person a) {
    double x = 1;
    for (String q : a.quirks) {
      var qd = p.lib.quirks().get(q.contains(":") ? q.substring(0, q.indexOf(':')) : q);
      if (qd != null) x *= qd.mechanics().arcMultipliers().getOrDefault(def.id(), 1.0);
    }
    return x;
  }

  /** Already in this arc or an exclusive one, recently done with it, or over the major-event cap. */
  private static boolean busy(Plan p, ArcDef def, Person a, long t) {
    for (var other : p.w.arcsOf(a.id)) {
      if (other.defId.equals(def.id()) || def.exclusiveWith().contains(other.defId)) return true;
      var od = p.lib.arcs().get(other.defId);
      if (od != null && od.exclusiveWith().contains(def.id())) return true;
    }
    int cooldown = def.recurrence() != null && !def.recurrence().cooldownDays().isEmpty()
        ? def.recurrence().cooldownDays().getFirst() : 60;
    for (var other : p.w.arcs.values()) {
      if (other.defId.equals(def.id()) && other.involves(a.id) && other.endedAt != null && t - other.endedAt < cooldown * DAY) {
        return true;
      }
    }
    if (def.category() == Category.LIFE || def.category() == Category.DRAMA) {
      var majors = p.w.majorEvents.get(a.id);
      if (majors != null && majors.tailSet(t - 14 * DAY).size() >= p.k.maxMajorEventsPer14d()) return true;
    }
    // at most one new arc per resident per slice
    for (var e : p.script.events) {
      if (e.payloadJson().contains("\"START\"") && e.payloadJson().contains(a.id)) return true;
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static boolean roleOk(Plan p, ArcDef def, String role, Person x, Person a) {
    var req = def.roles().get(role).requires();
    for (var e : req.entrySet()) {
      Object v = e.getValue();
      switch (e.getKey()) {
        case "relationship_status" -> {
          var allowed = v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(String.valueOf(v));
          if (!allowed.contains(x.status.name())) return false;
        }
        case "single" -> {
          if (Boolean.TRUE.equals(v) == x.attached()) return false;
        }
        case "has_partner" -> {
          if (Boolean.TRUE.equals(v) != x.attached()) return false;
        }
        case "quirk" -> {
          if (!x.hasQuirk(String.valueOf(v))) return false;
        }
        case "has_entity" -> {
          if (!Eligibility.owns(p.w, x, app.radiance.content.Vocab.EntityKind.valueOf(String.valueOf(v)))) return false;
        }
        case "employed" -> {
          if (Boolean.TRUE.equals(v) == p.w.activeTags(x.id, p.slice.start(), p.lib).contains("unemployed")) return false;
        }
        case "unemployed" -> {
          if (Boolean.TRUE.equals(v) != p.w.activeTags(x.id, p.slice.start(), p.lib).contains("unemployed")) return false;
        }
        case "circle" -> {
          if (!x.circles.contains(String.valueOf(v))) return false;
        }
        case "trait_min" -> {
          for (var t : ((Map<String, Object>) v).entrySet()) if (x.trait(t.getKey()) < ((Number) t.getValue()).doubleValue()) return false;
        }
        case "trait_max" -> {
          for (var t : ((Map<String, Object>) v).entrySet()) if (x.trait(t.getKey()) > ((Number) t.getValue()).doubleValue()) return false;
        }
        case "attracted_to" -> {
          if (a == null || x.gender == a.gender) return false;
          var e1 = p.w.edgeOrNull(x.id, a.id);
          if (e1 == null || e1.attraction < 40) return false;
          if (Boolean.TRUE.equals(req.get("mutual"))) {
            var e2 = p.w.edgeOrNull(a.id, x.id);
            if (e2 == null || e2.attraction < 40) return false;
          }
        }
        case "not" -> {
          if ("partner_of_a".equals(v) && a != null && x.id.equals(a.partnerId)) return false;
        }
        case "relationship_with" -> {
          if (a == null) return false;
          var e1 = p.w.edgeOrNull(x.id, a.id);
          var m = (Map<String, Object>) v;
          double aff = e1 == null ? 0 : e1.affinity;
          if (m.get("affinity_max") instanceof Number n && aff > n.doubleValue()) return false;
          if (m.get("affinity_min") instanceof Number n && aff < n.doubleValue()) return false;
        }
        default -> { }
      }
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  private static boolean startOk(Plan p, ArcDef def, Person a, Person b) {
    for (var e : def.start().requires().entrySet()) {
      Object v = e.getValue();
      var edge = b == null ? null : p.w.edgeOrNull(a.id, b.id);
      switch (e.getKey()) {
        case "chemistry_min" -> {
          if (edge == null || edge.chemistry < ((Number) v).doubleValue()) return false;
        }
        case "faithfulness_a_max" -> {
          if (a.trait("faithfulness") > ((Number) v).doubleValue()) return false;
        }
        case "affinity_min" -> {
          if (edge == null || edge.affinity < ((Number) v).doubleValue()) return false;
        }
        case "affinity_max" -> {
          if (edge == null || edge.affinity > ((Number) v).doubleValue()) return false;
        }
        case "familiarity_min" -> {
          if (edge == null || edge.familiarity < ((Number) v).doubleValue()) return false;
        }
        case "trait_min" -> {
          for (var t : ((Map<String, Object>) v).entrySet()) if (a.trait(t.getKey()) < ((Number) t.getValue()).doubleValue()) return false;
        }
        case "trait_max" -> {
          for (var t : ((Map<String, Object>) v).entrySet()) if (a.trait(t.getKey()) > ((Number) t.getValue()).doubleValue()) return false;
        }
        case "season" -> {
          if (!app.radiance.world.Calendar.season(p.w.date(p.slice.start())).equals(String.valueOf(v))) return false;
        }
        case "weather" -> {
          if (p.in.weather() == null || !p.in.weather().holds(String.valueOf(v), p.w.date(p.slice.start()))) return false;
        }
        default -> { }
      }
    }
    if (def.id().equals("romance") || def.id().equals("affair")) return b != null && a.gender != b.gender;
    return true;
  }

  /** Plans a START event for {@code defId} with {@code subject} in its first role. */
  static void start(Plan p, String defId, String subject, String other, long t, String cause) {
    if (p.lib.isBlacklisted(defId)) return;
    var def = p.lib.arcs().get(defId);
    if (def == null || def.phases().isEmpty()) return;
    var roleNames = new ArrayList<>(def.roles().keySet());
    var roles = new TreeMap<String, String>();
    roles.put(roleNames.getFirst(), subject);
    if (roleNames.size() > 1 && other != null) roles.put(roleNames.get(1), other);
    String arcId = p.id("arc", t, defId + ":" + subject + ":" + other);
    var data = new LinkedHashMap<String, Object>();
    var rng = RandomSource.seeded(p.w.seed).derive("arc-data:" + arcId);
    switch (defId) {
      case "injury_recovery" -> data.put("part", rng.pick(BODY_PARTS));
      case "new_hobby" -> data.put("hobby", rng.pick(HOBBIES));
      case "training_goal" -> data.put("goal", rng.pick(GOALS));
      case "school" -> data.put("program", rng.pick(PROGRAMS));
      case "home_project" -> p.w.things.values().stream()
          .filter(x -> subject.equals(x.ownerId) && x.kind.name().equals("HOME") && x.current()).findFirst()
          .ifPresent(x -> data.put("entity", x.id));
      default -> { }
    }
    if (cause != null) data.put("cause", cause);
    var first = def.phases().getFirst();
    var arc = new Arc(arcId, defId, app.radiance.story.ArcRow.Status.ACTIVE, first.id(),
        first.visibility() == null ? Visibility.PUBLIC : first.visibility(), t, t, null, t, roles, data, null, null, null);
    Long next = next(p, arcId, first, t);
    var entry = first.entryEvent();
    String summary = names(p.w, arc) + ": " + defId.replace('_', ' ') + " (" + first.id().replace('_', ' ') + ")";
    var change = new EventPayload.ArcChange(arcId, defId, "START", null, first.id(), arc.visibility.name(), next,
        "ACTIVE", knowers(p.w, arc, first), roles, data, summary);
    int importance = entry != null ? entry.importance() : 3;
    var payload = new EventPayload(def.category().name(), roles, entry != null ? entry.effects() : Map.of(), change, null,
        List.of(), null, def.description() + " — " + first.id().replace('_', ' '));
    var ev = p.event("arc-start:" + arcId, t, entry != null ? entry.type() : "ARC_STARTED", defId, subject, other, arcId,
        importance, arc.visibility, EventRow.Source.ARC, payload);
    if (entry != null && entry.post() != null && entry.post() != PostOutput.NONE && entry.post() != PostOutput.DM
        && arc.visibility == Visibility.PUBLIC && postsAllowed(p, importance)) {
      var brief = new Brief(defId, def.category().name(), def.description() + " — " + first.id().replace('_', ' '), null,
          null, null, topics(def), 0.2, 0.2, Plan.extra("valence", valence(entry.type()), "arcPhase", first.id(),
          "roles", roles, "data", data));
      p.post("arc-start:" + arcId, t + 60_000L, subject, PostRow.Kind.valueOf(entry.post().name()), brief, arcId, ev.id(),
          null, importance);
    }
  }

  // ------------------------------------------------------------------------------------- beats

  private static void beats(Plan p) {
    long t0 = p.slice.start(), t1 = p.slice.end();
    dailyBeat(p);
    for (var arc : p.w.arcs.values()) {
      if (!arc.active() || arc.isNew) continue;
      var def = p.lib.arcs().get(arc.defId);
      var phase = def == null ? null : phase(def, arc.phase);
      if (phase == null) continue;
      if (transitionsThisSlice(p, arc)) continue;
      long last = arc.lastBeatAt == null ? arc.phaseEnteredAt : Math.max(arc.lastBeatAt, arc.phaseEnteredAt);
      boolean forced = arc.visibility == Visibility.PUBLIC && t1 - last > (def.maxSilenceDays() - 1) * DAY;
      if (phase.beats().isEmpty()) {
        // invariant 2: a PUBLIC arc still gets a beat, even from a phase with none of its own
        if (forced) beat(p, arc, def, -1, new app.radiance.content.Defs.Beat(BeatKind.MEMORY,
            "life goes on: " + arc.phase.replace('_', ' '), null), t0 + 5 * 60_000L);
        continue;
      }
      double lambda = p.slice.hours() / 24.0 * 2.0 / Math.max(1, def.maxSilenceDays());
      var rng = p.rng.derive("beat:" + arc.id);
      if (!forced && !rng.chance(lambda)) continue;
      int i = rng.nextInt(phase.beats().size());
      var beat = phase.beats().get(i);
      if (beat.maxPerWeek() != null) {
        var fired = p.w.fired.get(arc.id + "|beat:" + i);
        if (fired != null && fired.tailSet(t0 - 7 * DAY).size() >= beat.maxPerWeek() && !forced) continue;
      }
      long t = forced ? t0 + 5 * 60_000L : t0 + rng.nextInt((int) (t1 - t0));
      beat(p, arc, def, i, beat, t);
    }
  }

  /**
   * The storyteller keeps a storyline visible every world-day: if nothing public has moved by the
   * evening slice, the stalest PUBLIC arc gets a beat.
   */
  private static void dailyBeat(Plan p) {
    var local = java.time.Instant.ofEpochMilli(p.slice.start()).atZone(p.w.zone);
    long evening = local.toLocalDate().atTime(18, 0).atZone(p.w.zone).toInstant().toEpochMilli();
    if (p.slice.start() > evening || p.slice.end() <= evening) return;
    long dayStart = local.toLocalDate().atStartOfDay(p.w.zone).toInstant().toEpochMilli();
    Arc stalest = null;
    for (var arc : p.w.arcs.values()) {
      if (!arc.active() || arc.visibility != Visibility.PUBLIC) continue;
      long last = arc.lastBeatAt == null ? arc.phaseEnteredAt : Math.max(arc.lastBeatAt, arc.phaseEnteredAt);
      if (last >= dayStart) return;
      if (stalest == null || last < (stalest.lastBeatAt == null ? stalest.phaseEnteredAt : stalest.lastBeatAt)) stalest = arc;
    }
    for (var e : p.script.events) if (e.arcId() != null && e.visibility() == Visibility.PUBLIC) return;
    if (stalest == null) return;
    var def = p.lib.arcs().get(stalest.defId);
    var phase = def == null ? null : phase(def, stalest.phase);
    if (phase == null) return;
    var beat = phase.beats().isEmpty() ? new app.radiance.content.Defs.Beat(BeatKind.MEMORY,
        "life goes on: " + stalest.phase.replace('_', ' '), null) : phase.beats().getFirst();
    beat(p, stalest, def, phase.beats().isEmpty() ? -1 : 0, beat, Math.max(p.slice.start(), evening) + 10 * 60_000L);
  }

  private static boolean transitionsThisSlice(Plan p, Arc arc) {
    for (var e : p.script.events) if (arc.id.equals(e.arcId())) return true;
    return false;
  }

  private static void beat(Plan p, Arc arc, ArcDef def, int index, app.radiance.content.Defs.Beat beat, long t) {
    String subject = arc.roles.firstEntry().getValue();
    var person = p.w.people.get(subject);
    if (person == null) return;
    String text = beat.brief().replace("{hobby}", String.valueOf(arc.data.getOrDefault("hobby", "")));
    var payload = new EventPayload(def.category().name(), arc.roles, Map.of(),
        new EventPayload.ArcChange(arc.id, def.id(), "BEAT", arc.phase, arc.phase, arc.visibility.name(),
            arc.nextTransitionAt, "ACTIVE", List.of(), arc.roles, Map.of(), null), null, List.of(), null, text);
    var kind = beat.kind();
    boolean post = kind == BeatKind.STATUS || kind == BeatKind.PHOTO || kind == BeatKind.LINK
        || kind == BeatKind.LIFE_EVENT || kind == BeatKind.CHECKIN || kind == BeatKind.MENTION;
    String author = subject;
    // a lurker's beats surface through a friend's post, as forced beats may (SPEC §5.4)
    if (post && kind != BeatKind.MENTION && person.routine.postsPerDay() < 0.15) kind = BeatKind.MENTION;
    if (kind == BeatKind.MENTION) author = p.w.closestFriend(subject);
    // invariant 7: a private or secret beat only surfaces through someone who knows
    if (author != null && !author.equals(subject) && arc.visibility != Visibility.PUBLIC
        && !arc.involves(author) && !p.w.knows("ARC", arc.id, author)) author = null;
    if (author == null) post = false;
    int importance = post ? 3 : 2;
    var ev = p.event("beat:" + arc.id + ":" + t, t, "ARC_BEAT", def.id(), subject, null, arc.id, importance,
        arc.visibility, EventRow.Source.ARC, payload);
    // the copy's fired map counts beats for max_per_week; airing rebuilds it from the event log
    p.w.fired.computeIfAbsent(arc.id + "|beat:" + index, k -> new java.util.TreeSet<>()).add(t);
    if (post && (p.slice.fidelity() != app.radiance.kernel.Fidelity.SUMMARY)) {
      var brief = new Brief(def.id(), def.category().name(), text, null, null, null, topics(def), 0.1, 0.1,
          Plan.extra("arcPhase", arc.phase, "beat", kind.name(), "secret", arc.visibility == Visibility.SECRET ? true : null,
              "mentions", kind == BeatKind.MENTION ? List.of(subject) : null, "data", arc.data));
      var postKind = kind == BeatKind.MENTION ? PostRow.Kind.STATUS : PostRow.Kind.valueOf(kind.name());
      p.post("beat:" + arc.id + ":" + t, t + 30_000L, author, postKind, brief, arc.id, ev.id(), null, importance);
    }
  }

  // ------------------------------------------------------------------------------------- helpers

  static Phase phase(ArcDef def, String id) {
    for (var ph : def.phases()) if (ph.id().equals(id)) return ph;
    return null;
  }

  private static boolean postsAllowed(Plan p, int importance) {
    if (p.structureOnly()) return false;
    return p.slice.fidelity() != app.radiance.kernel.Fidelity.SUMMARY || importance >= 5;
  }

  static String names(SimWorld w, Arc arc) {
    var out = new ArrayList<String>();
    for (String id : arc.roles.values()) {
      var person = w.people.get(id);
      out.add(person == null ? id : person.name);
    }
    return String.join(" and ", out);
  }

  static List<String> topics(ArcDef def) {
    return switch (def.id()) {
      case "car_saga" -> List.of("cars");
      case "home_project" -> List.of("home_improvement");
      case "training_goal", "injury_recovery" -> List.of("fitness");
      case "new_pet" -> List.of("pets");
      case "job_loss", "job_search", "side_hustle" -> List.of("personal_finance");
      default -> List.of();
    };
  }

  /** Good news, bad news or neither, by the entry event's type. */
  static double valence(String type) {
    String t = type.toUpperCase();
    if (t.contains("BREAK") || t.contains("LOST") || t.contains("LAID") || t.contains("INJUR") || t.contains("SPLIT")
        || t.contains("LOSS") || t.contains("SETBACK") || t.contains("DISCOVERED") || t.contains("REJECT")) return -0.8;
    if (t.contains("OFFICIAL") || t.contains("ENGAGE") || t.contains("WEDDING") || t.contains("PURCHASED")
        || t.contains("ADOPT") || t.contains("OFFER") || t.contains("LANDED") || t.contains("FINISHED")
        || t.contains("ACCEPT") || t.contains("MILESTONE") || t.contains("RECOVER") || t.contains("SOFT_LAUNCH")
        || t.contains("MOVED")) return 0.8;
    return 0;
  }

  /** A new entity from a curated table (SPEC §5.5): a car, a pet. */
  static EventPayload.NewThing newThing(Plan p, Arc arc, String kind, String table, long t) {
    var rows = p.lib.tables().get(table);
    if (rows == null || rows.isEmpty()) return null;
    var rng = RandomSource.seeded(p.w.seed).derive("thing:" + arc.id + ":" + t);
    var row = new LinkedHashMap<>(rows.get(rng.nextInt(rows.size())));
    String owner = arc.roles.firstEntry().getValue();
    String id = p.id("entity", t, arc.id + ":" + kind);
    var ownerP = p.w.people.get(owner);
    String place = ownerP != null && ownerP.livesIn != null ? ownerP.livesIn : "a Chicago suburb";
    if (kind.equals("CAR")) {
      String name = "the " + row.get("make") + " " + row.get("model");
      String look = "a " + row.get("color") + " " + row.get("year") + " " + row.get("make") + " " + row.get("model") + " with "
          + row.get("flaw") + ", parked on a street in " + place;
      return new EventPayload.NewThing(id, owner, kind, name, look, rng.nextInt(Integer.MAX_VALUE), row);
    }
    if (kind.equals("PET")) {
      String petName = "Biscuit";
      for (var r : p.lib.tables().getOrDefault("pet_names", List.of())) {
        if (r.get("species").equals(row.get("species")) && r.get("names") instanceof List<?> l && !l.isEmpty()) {
          petName = String.valueOf(l.get(rng.nextInt(l.size())));
        }
      }
      row.put("name", petName);
      return new EventPayload.NewThing(id, owner, kind, petName, String.valueOf(row.get("look")), rng.nextInt(Integer.MAX_VALUE),
          row);
    }
    return new EventPayload.NewThing(id, owner, kind, String.valueOf(row.values().iterator().next()), null,
        rng.nextInt(Integer.MAX_VALUE), row);
  }

  static boolean single(Person p) {
    return p.status == StatusKind.SINGLE || p.status == StatusKind.DIVORCED || p.status == StatusKind.WIDOWED;
  }
}
