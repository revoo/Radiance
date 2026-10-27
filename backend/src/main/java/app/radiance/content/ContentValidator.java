package app.radiance.content;

import app.radiance.content.Defs.ArcDef;
import app.radiance.content.Defs.Conditions;
import app.radiance.content.Defs.EventDef;
import app.radiance.content.Defs.Phase;
import app.radiance.content.Vocab.Gender;
import app.radiance.content.Vocab.StatusKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Cross-file checks over a parsed library. Returns every problem; an empty list means valid. */
final class ContentValidator {

  private static final Pattern DELTA_KEY = Pattern.compile("[a-z_]+ ?(->|<->) ?[a-z_]+");
  private static final Pattern REL_TAG_EFFECT = Pattern.compile("([a-z_]+) ?(->|<->) ?([a-z_]+): ?([a-z_]+)(:[a-z0-9_]+)?");
  private static final Pattern PINNED_TAG = Pattern.compile("([a-z_]+)(?::([a-z0-9_]+))? -> (.+)");
  private static final Set<String> SEASONS = Set.of("winter", "spring", "summer", "fall");
  private static final Set<String> RELATIONSHIP_KINDS = Set.of(
      "STRANGER", "ACQUAINTANCE", "FRIEND", "CLOSE_FRIEND", "FAMILY", "COWORKER", "PARTNER", "EX", "RIVAL");

  private final ContentLibrary lib;
  private final List<String> problems = new ArrayList<>();

  private ContentValidator(ContentLibrary lib) {
    this.lib = lib;
  }

  static List<String> validate(ContentLibrary lib) {
    var v = new ContentValidator(lib);
    v.quirks();
    v.constraints();
    v.events();
    v.arcs();
    v.rules();
    v.roster();
    v.tables();
    return v.problems;
  }

  private void problem(String where, String what) {
    problems.add(where + ": " + what);
  }

  // ------------------------------------------------------------------------------------------

  private void quirks() {
    lib.quirks().forEach((id, q) -> {
      String w = "quirk " + id;
      if (q.description() == null || q.description().isBlank()) problem(w, "needs a description");
      q.mechanics().arcMultipliers().keySet().forEach(arc -> {
        if (!lib.arcs().containsKey(arc)) problem(w, "arc_multipliers names unknown arc '" + arc + "'");
      });
      q.mechanics().eventMultipliers().keySet().forEach(ev -> {
        if (!lib.events().containsKey(ev) && !eventTags().contains(ev.toLowerCase())) {
          problem(w, "event_multipliers names unknown event or event tag '" + ev + "'");
        }
      });
      q.mechanics().traitNudges().keySet().forEach(t -> {
        if (!Vocab.TRAITS.contains(t)) problem(w, "trait_nudges names unknown trait '" + t + "'");
      });
    });
  }

  private Set<String> eventTags() {
    var tags = new HashSet<String>();
    lib.events().values().forEach(e -> tags.addAll(e.tags()));
    return tags;
  }

  private void constraints() {
    lib.constraints().forEach((id, c) -> {
      String w = "constraint " + id;
      if (c.prose() == null || c.prose().isBlank()) problem(w, "needs a prose line for briefs");
      if (c.linter() == null || c.linter().lexicon().isEmpty()) problem(w, "needs a linter lexicon");
      if (c.linter() != null && (c.linter().judge() == null || c.linter().judge().isBlank())) {
        problem(w, "needs a linter judge question");
      }
    });
  }

  /** {@code tag} or {@code tag:param} or {@code tag:{placeholder}}; the tag must exist. */
  private void constraintRef(String where, String ref) {
    String id = ref.contains(":") ? ref.substring(0, ref.indexOf(':')) : ref;
    var def = lib.constraints().get(id);
    if (def == null) problem(where, "unknown constraint tag '" + id + "'");
    else if (ref.contains(":") && def.params().isEmpty()) {
      problem(where, "constraint '" + id + "' takes no parameter: '" + ref + "'");
    }
  }

  // ------------------------------------------------------------------------------------------

  private void events() {
    var weather = lib.weather() == null ? Map.<String, Rules.WeatherCondition>of() : lib.weather();
    lib.events().forEach((id, e) -> {
      String w = "event " + id;
      if (e.category() == null) problem(w, "needs a category");
      if (e.baseRate() < 0) problem(w, "base_rate must be >= 0");
      if (e.importance() < 1 || e.importance() > 10) problem(w, "importance must be 1..10");
      if (e.output() == null || e.output().post() == null) problem(w, "needs output.post");
      if (e.output() != null && e.output().post() != Vocab.PostOutput.NONE
          && (e.output().brief() == null || e.output().brief().isBlank())) {
        problem(w, "posts need a brief");
      }
      if (e.category() == Vocab.Category.NEWS && (e.trigger() == null || e.trigger().headline() == null)) {
        problem(w, "NEWS events need trigger.headline");
      }
      if (e.cooldownDays() != null && e.cooldownDays() < 0) problem(w, "cooldown_days must be >= 0");
      e.modifiers().keySet().forEach(k -> modifierKey(w, k));
      conditions(w + " requires", e.requires());
      conditions(w + " excludes", e.excludes());
      e.participants().forEach((role, sel) -> {
        try {
          Selector.parse(sel);
        } catch (IllegalArgumentException ex) {
          problem(w, "participant '" + role + "': " + ex.getMessage());
        }
      });
      if (e.when() != null) {
        e.when().calendar().forEach(c -> {
          if (!Vocab.CALENDAR.contains(c)) problem(w, "unknown calendar trigger '" + c + "'");
        });
        if (e.when().weather() != null && !weather.containsKey(e.when().weather())) {
          problem(w, "unknown weather condition '" + e.when().weather() + "'");
        }
        e.when().seasons().forEach(s -> {
          if (!SEASONS.contains(s)) problem(w, "unknown season '" + s + "'");
        });
      }
      e.tags().forEach(t -> {
        if (!t.equals(t.toLowerCase())) problem(w, "event tags are lowercase: '" + t + "'");
      });
      effects(w, e.effects());
    });
  }

  private void modifierKey(String where, String key) {
    if (Vocab.TRAITS.contains(key) || Vocab.STATE.contains(key) || key.equals("tension")) return;
    if (key.startsWith("quirk.")) {
      if (!lib.quirks().containsKey(key.substring(6))) problem(where, "modifier names unknown quirk '" + key + "'");
      return;
    }
    if (key.startsWith("season.") && SEASONS.contains(key.substring(7))) return;
    if (key.startsWith("temperament.")) {
      try {
        Vocab.Temperament.valueOf(key.substring(12).toUpperCase());
        return;
      } catch (IllegalArgumentException ignored) {
        // fall through
      }
    }
    if (key.startsWith("interest.")) {
      String topic = key.substring(9);
      if (Vocab.TOPICS.contains(topic) || topic.startsWith("sports")) return;
    }
    if (key.startsWith("observance.")) {
      try {
        Vocab.Observance.valueOf(key.substring(11).toUpperCase());
        return;
      } catch (IllegalArgumentException ignored) {
        // fall through
      }
    }
    if (key.equals("has_kids") || key.equals("has_partner") || key.equals("weekend")) return;
    problem(where, "unknown modifier '" + key + "'");
  }

  private void conditions(String where, Conditions c) {
    c.tags().forEach(t -> constraintRef(where, t));
    c.arcs().forEach(a -> arcRef(where, a));
    c.quirks().forEach(q -> {
      if (!lib.quirks().containsKey(q)) problem(where, "unknown quirk '" + q + "'");
    });
  }

  /** {@code arc} or {@code arc:phase}. */
  private void arcRef(String where, String ref) {
    String id = ref.contains(":") ? ref.substring(0, ref.indexOf(':')) : ref;
    var arc = lib.arcs().get(id);
    if (arc == null) {
      problem(where, "unknown arc '" + id + "'");
      return;
    }
    if (ref.contains(":")) {
      String phase = ref.substring(ref.indexOf(':') + 1);
      if (arc.phases().stream().noneMatch(p -> p.id().equals(phase))) {
        problem(where, "arc '" + id + "' has no phase '" + phase + "'");
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void effects(String where, Map<String, Object> effects) {
    effects.forEach((key, value) -> {
      if (DELTA_KEY.matcher(key).matches()) {
        if (!(value instanceof Map<?, ?> m)) {
          problem(where, "effect '" + key + "' must map dimensions to deltas");
          return;
        }
        m.keySet().forEach(dim -> {
          if (!Selector.DIMENSIONS.contains(String.valueOf(dim))) {
            problem(where, "effect '" + key + "' has unknown dimension '" + dim + "'");
          }
        });
        return;
      }
      if (!Vocab.EFFECT_KEYS.contains(key)) {
        problem(where, "unknown effect '" + key + "'");
        return;
      }
      switch (key) {
        case "start_arc" -> {
          String arc = String.valueOf(value);
          if (!lib.arcs().containsKey(arc)) problem(where, "start_arc names unknown arc '" + arc + "'");
          if (lib.isBlacklisted(arc)) problem(where, "start_arc names blacklisted arc '" + arc + "'");
        }
        case "add_constraints", "remove_constraints" -> {
          for (Object c : asList(value)) constraintRef(where, String.valueOf(c));
        }
        case "add_relationship_tags", "remove_relationship_tags" -> {
          for (Object t : asList(value)) {
            var m = REL_TAG_EFFECT.matcher(String.valueOf(t));
            if (!m.matches() || !Vocab.RELATIONSHIP_TAGS.contains(m.group(4))) {
              problem(where, "bad relationship tag effect '" + t + "' (want 'a -> b: tag')");
            }
          }
        }
        case "relationship_kind" -> {
          if (!RELATIONSHIP_KINDS.contains(String.valueOf(value))) {
            problem(where, "unknown relationship_kind '" + value + "'");
          }
        }
        case "relationship_status" -> {
          try {
            StatusKind.valueOf(String.valueOf(value));
          } catch (IllegalArgumentException e) {
            problem(where, "unknown relationship_status '" + value + "'");
          }
        }
        case "trait_nudges" -> {
          if (value instanceof Map<?, ?> m) {
            ((Map<String, Object>) m).keySet().forEach(t -> {
              if (!Vocab.TRAITS.contains(t)) problem(where, "trait_nudges names unknown trait '" + t + "'");
            });
          }
        }
        default -> { }
      }
    });
  }

  private static List<?> asList(Object value) {
    return value instanceof Collection<?> c ? List.copyOf(c) : List.of(value);
  }

  // ------------------------------------------------------------------------------------------

  private void arcs() {
    lib.arcs().forEach((id, arc) -> {
      String w = "arc " + id;
      if (arc.category() == null) problem(w, "needs the event-mix category its beats count toward");
      if (arc.maxSilenceDays() <= 0) problem(w, "max_silence_days must be > 0");
      if (arc.roles().isEmpty()) problem(w, "needs at least one role");
      arc.roles().forEach((role, r) -> r.requires().keySet().forEach(k -> {
        if (!Vocab.ROLE_REQUIREMENTS.contains(k)) problem(w, "role '" + role + "' has unknown requirement '" + k + "'");
      }));
      if (arc.start() != null) {
        if (arc.start().baseRatePerYear() < 0) problem(w, "start.base_rate_per_year must be >= 0");
        arc.start().requires().keySet().forEach(k -> {
          if (!Vocab.START_REQUIREMENTS.contains(k)) problem(w, "start has unknown requirement '" + k + "'");
        });
      }
      arc.exclusiveWith().forEach(x -> {
        if (!lib.arcs().containsKey(x)) problem(w, "exclusive_with names unknown arc '" + x + "'");
      });
      if (arc.recurrence() != null && arc.recurrence().cooldownDays().size() != 2) {
        problem(w, "recurrence.cooldown_days must be [min, max]");
      }
      phases(w, arc);
    });
  }

  private void phases(String w, ArcDef arc) {
    var ids = new HashSet<String>();
    for (Phase p : arc.phases()) {
      if (!ids.add(p.id())) problem(w, "duplicate phase '" + p.id() + "'");
    }
    if (arc.phases().isEmpty()) {
      problem(w, "needs phases");
      return;
    }
    if (arc.abandon() != null) {
      var target = arc.phases().stream().filter(p -> p.id().equals(arc.abandon())).findFirst();
      if (target.isEmpty() || !target.get().terminal()) {
        problem(w, "abandon must name a terminal phase, not '" + arc.abandon() + "'");
      }
    }
    for (Phase p : arc.phases()) {
      String pw = w + " phase " + p.id();
      if (p.terminal()) {
        if (!p.transitions().isEmpty() || p.next() != null) problem(pw, "terminal phases don't transition");
        if (p.entryEvent() == null) problem(pw, "terminal phases need an entry_event (no silent endings)");
      } else {
        if (p.durationDays().size() != 2 || p.durationDays().get(0) > p.durationDays().get(1)) {
          problem(pw, "duration_days must be [min, max]");
        }
        if (p.next() == null && p.transitions().isEmpty()) problem(pw, "needs next or transitions");
        if (p.next() != null && !p.transitions().isEmpty()) problem(pw, "has both next and transitions");
        if (p.next() != null && !ids.contains(p.next())) problem(pw, "next names unknown phase '" + p.next() + "'");
        double sum = 0;
        for (var t : p.transitions()) {
          sum += t.p();
          if (!ids.contains(t.to())) problem(pw, "transition to unknown phase '" + t.to() + "'");
        }
        if (!p.transitions().isEmpty() && Math.abs(sum - 1.0) > 0.01) {
          problem(pw, "transition probabilities sum to " + sum + ", not 1");
        }
      }
      for (String k : p.knowers()) {
        String role = k.startsWith("closest_friend_of:") ? k.substring(18) : k;
        if (!arc.roles().containsKey(role)) problem(pw, "knower names unknown role '" + k + "'");
      }
      for (String c : p.constraints()) constraintRef(pw, c.replaceAll("\\{[a-z_]+}", "x"));
      if (p.entryEvent() != null) {
        var ce = p.entryEvent().createsEntity();
        if (ce != null && (ce.fromTable() == null || !lib.tables().containsKey(ce.fromTable()))) {
          problem(pw, "creates_entity names unknown table '" + (ce == null ? null : ce.fromTable()) + "'");
        }
        if (p.entryEvent().type() == null) problem(pw, "entry_event needs a type");
        if (p.entryEvent().importance() < 1 || p.entryEvent().importance() > 10) {
          problem(pw, "entry_event importance must be 1..10");
        }
        if (p.entryEvent().post() == null) problem(pw, "entry_event needs post (NONE for memories only)");
        effects(pw, p.entryEvent().effects());
      }
      for (var b : p.beats()) if (b.kind() == null || b.brief() == null) problem(pw, "beats need kind and brief");
    }
    // every phase can reach a terminal phase
    var graph = new HashMap<String, List<String>>();
    for (Phase p : arc.phases()) {
      var next = new ArrayList<String>();
      if (p.next() != null) next.add(p.next());
      p.transitions().forEach(t -> next.add(t.to()));
      graph.put(p.id(), next);
    }
    var terminals = arc.phases().stream().filter(Phase::terminal).map(Phase::id).toList();
    if (terminals.isEmpty()) problem(w, "needs at least one terminal phase");
    for (Phase p : arc.phases()) {
      if (!reaches(graph, p.id(), terminals)) problem(w, "phase " + p.id() + " can't reach an ending");
    }
  }

  private static boolean reaches(Map<String, List<String>> graph, String from, List<String> goals) {
    var seen = new HashSet<String>();
    var queue = new ArrayDeque<String>(List.of(from));
    while (!queue.isEmpty()) {
      String n = queue.poll();
      if (goals.contains(n)) return true;
      if (seen.add(n)) queue.addAll(graph.getOrDefault(n, List.of()));
    }
    return false;
  }

  // ------------------------------------------------------------------------------------------

  private void tables() {
    for (String required : java.util.List.of("names", "kid_names", "pet_names")) {
      if (!lib.tables().containsKey(required)) problem("tables/" + required + ".yaml", "missing");
    }
  }

  private void rules() {
    var t = lib.temperament();
    for (var preset : Vocab.Temperament.values()) {
      var p = t.presets().get(preset);
      if (p == null) {
        problem("rules/temperament.yaml", "missing preset " + preset);
      } else if (!(p.floor() <= p.baseline() && p.baseline() <= p.ceiling())) {
        problem("rules/temperament.yaml", preset + " needs floor <= baseline <= ceiling");
      }
    }
    var e = lib.engagement();
    if (e.thetaComment() <= e.thetaReact()) problem("rules/engagement.yaml", "theta_comment must exceed theta_react");
    var pacing = lib.pacing();
    var durations = new ArrayList<String>();
    if (pacing.responseCurve() != null) {
      var rc = pacing.responseCurve();
      if (rc.reflexReaction() != null) {
        durations.addAll(rc.reflexReaction().first());
        durations.addAll(rc.reflexReaction().spacing());
      }
      if (rc.comment() != null) {
        durations.addAll(rc.comment().first());
        durations.add(rc.comment().tail());
      }
      if (rc.replyToR() != null) durations.addAll(rc.replyToR().addresseeFirst());
    } else {
      problem("rules/pacing.yaml", "needs response_curve");
    }
    if (pacing.hooks() != null) {
      durations.add(pacing.hooks().quietGap());
      durations.add(pacing.hooks().minInterval());
    }
    if (pacing.breathingRoom() != null) {
      durations.add(pacing.breathingRoom().within());
      durations.addAll(pacing.breathingRoom().pause());
    }
    if (pacing.dm() != null) {
      durations.addAll(pacing.dm().typingStarts());
      durations.add(pacing.dm().maxTypingDelay());
      durations.addAll(pacing.dm().burstGap());
    }
    if (pacing.typing() != null) durations.add(pacing.typing().giveUpAfter());
    pacing.hold().forEach((k, v) -> {
      if (!v.equals("until_next_slot")) durations.add(v);
    });
    for (String d : durations) {
      try {
        Durations.parse(d);
      } catch (IllegalArgumentException ex) {
        problem("rules/pacing.yaml", ex.getMessage());
      }
    }
    lib.relationships().interactions().keySet().forEach(k -> {
      if (!k.equals(k.toLowerCase())) problem("rules/relationship-deltas.yaml", "interaction names are lowercase: " + k);
    });
  }

  // ------------------------------------------------------------------------------------------

  private void roster() {
    var r = lib.roster();
    String w = "cast/roster.yaml";
    if (r.residents().size() != 30) problem(w, "needs exactly 30 residents, has " + r.residents().size());
    if (r.you() == null) problem(w, "needs a 'you' entry");
    var byName = new HashMap<String, Roster.Resident>();
    var handles = new HashSet<String>();
    long women = 0;
    for (var res : r.residents()) {
      String rw = w + " " + res.name();
      if (res.name() == null || res.gender() == null || res.status() == null || res.status().kind() == null) {
        problem(rw, "needs name, gender and status");
        continue;
      }
      if (byName.put(res.name(), res) != null) problem(rw, "duplicate name");
      if (!handles.add(res.resolvedHandle())) problem(rw, "duplicate handle '" + res.resolvedHandle() + "'");
      if (!res.resolvedHandle().matches("[a-z]+(\\.[a-z]+)+")) {
        problem(rw, "handle must be lowercase ASCII words joined by dots: '" + res.resolvedHandle() + "'");
      }
      if (res.age() < 22 || res.age() > 70) problem(rw, "age must be 22..70, is " + res.age());
      if (res.gender() == Gender.F) women++;
      res.circles().forEach(c -> {
        if (!Vocab.CIRCLES.contains(c)) problem(rw, "unknown circle '" + c + "'");
      });
      if (res.circles().contains("family") != !res.family().isEmpty()) {
        problem(rw, "the family circle and family links go together");
      }
    }
    if (women != 15) problem(w, "needs 15 women and 15 men; has " + women + " women");
    for (var res : r.residents()) {
      if (res.name() == null || res.status() == null) continue;
      String rw = w + " " + res.name();
      var with = res.status().with();
      if (with != null) {
        var partner = byName.get(with);
        if (partner == null) {
          problem(rw, "partner '" + with + "' is not in the roster");
        } else {
          if (!res.name().equals(partner.status().with())) problem(rw, "partner '" + with + "' doesn't list them back");
          if (partner.gender() == res.gender()) problem(rw, "every couple pairs a man and a woman (invariant 11)");
        }
        if (res.status().kind() != StatusKind.DATING && res.status().kind() != StatusKind.MARRIED) {
          problem(rw, "only DATING or MARRIED statuses name a partner");
        }
      }
      for (var k : res.knows()) {
        if (!byName.containsKey(k.with())) problem(rw, "knows '" + k.with() + "', who is not in the roster");
      }
      for (var link : res.family()) {
        var rel = byName.get(link.with());
        if (rel == null) {
          problem(rw, "family '" + link.with() + "' is not in the roster");
        } else if (rel.family().stream().noneMatch(l -> res.name().equals(l.with()))) {
          problem(rw, "family '" + link.with() + "' doesn't list them back");
        }
      }
    }
    if (r.you() != null) {
      if (r.you().age() < 22 || r.you().age() > 70) problem(w + " you", "age must be 22..70");
      if (r.you().gender() == null) problem(w + " you", "needs a gender");
    }
    for (var p : r.pinned()) {
      String pw = w + " pinned " + p.name();
      if (!byName.containsKey(p.name())) {
        problem(pw, "not in the roster");
        continue;
      }
      if (p.seeds() == null) continue;
      for (String arc : p.seeds().arcs()) {
        String id = arc.contains(":") ? arc.substring(0, arc.indexOf(':')) : arc;
        if (lib.isBlacklisted(id)) problem(pw, "seeds blacklisted arc '" + id + "'");
        else arcRef(pw, arc);
      }
      for (String q : p.seeds().quirks()) {
        if (!lib.quirks().containsKey(q)) problem(pw, "seeds unknown quirk '" + q + "'");
      }
      for (String t : p.seeds().tags()) {
        var m = PINNED_TAG.matcher(t);
        if (!m.matches() || !Vocab.RELATIONSHIP_TAGS.contains(m.group(1))) {
          problem(pw, "bad tag seed '" + t + "' (want 'tag:param -> Name')");
        } else if (!byName.containsKey(m.group(3).strip())) {
          problem(pw, "tag seed names '" + m.group(3).strip() + "', who is not in the roster");
        }
      }
    }
  }
}
