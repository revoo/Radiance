package app.radiance.world.genesis;

import app.radiance.content.ContentLibrary;
import app.radiance.content.Roster;
import app.radiance.content.Vocab;
import app.radiance.content.Vocab.EntityKind;
import app.radiance.content.Vocab.Gender;
import app.radiance.content.Vocab.Observance;
import app.radiance.content.Vocab.StatusKind;
import app.radiance.content.Vocab.Temperament;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Visibility;
import app.radiance.world.genesis.WorldSeed.Arc;
import app.radiance.world.genesis.WorldSeed.Bias;
import app.radiance.world.genesis.WorldSeed.Entity;
import app.radiance.world.genesis.WorldSeed.InitialState;
import app.radiance.world.genesis.WorldSeed.OffNetworkPerson;
import app.radiance.world.genesis.WorldSeed.Politics;
import app.radiance.world.genesis.WorldSeed.Relationship;
import app.radiance.world.genesis.WorldSeed.Resident;
import app.radiance.world.genesis.WorldSeed.Routine;
import app.radiance.world.genesis.WorldSeed.TemperamentSeed;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Genesis step 1 (SPEC §6.4): the deterministic skeleton around the fixed roster — birth dates,
 * personalities, politics, biases, quirks, interests, taste, temperament, faithfulness, routines,
 * the relationship graph, starting arcs and entities. Same roster + library + settings ⇒ the same seed,
 * byte for byte. Personality never comes from demographics; nothing here reads heritage or faith
 * except names for off-network family and the {@code faith} interest for observant residents.
 */
public final class SkeletonGenerator {

  public record Settings(
      long seed, int grouches, int sweethearts, int strongPrejudice, LocalDate referenceDate) {}

  static final List<String> BIG_FIVE =
      List.of("openness", "conscientiousness", "extraversion", "agreeableness", "neuroticism");
  static final List<String> SOCIAL = List.of("argumentativeness", "attention_seeking", "humor",
      "nosiness", "sincerity", "flirtatiousness", "impulsivity", "optimism");
  static final List<String> TASTE = List.of("humor", "warmth", "intellect", "pretentiousness",
      "bragging", "vulnerability", "positivity", "edginess", "preachiness", "effort",
      "political_alignment");
  /** Signature quirks belong to their pinned residents; nobody else draws them. */
  static final Set<String> SIGNATURE =
      Set.of("serial_fixer_upper_buyer", "serial_flirt", "troll", "diy_overreacher");
  static final List<Set<String>> CONFLICTS = List.of(
      Set.of("lurker", "oversharer"), Set.of("lurker", "vaguebooker"),
      Set.of("wholesome_cheerleader", "grammar_police"), Set.of("wholesome_cheerleader", "troll"));
  static final List<String> EVERYDAY_BIAS_TARGETS = List.of("people from the suburbs",
      "city people", "boomers", "millennials", "Gen Z", "rich people", "people on welfare",
      "liberals", "conservatives", "hipsters", "Cubs fans", "Sox fans", "downstate Illinois",
      "Naperville moms", "tech bros", "cops", "lawyers");
  static final List<String> STRONG_BIAS_TARGETS = List.of("immigrants", "Muslims", "Mexicans",
      "Black people", "white people", "Jews", "Christians", "women", "men", "gay people",
      "trans people", "conservatives", "liberals", "Poles", "Indians");
  static final List<String> STYLES = List.of("JOKES", "DOG_WHISTLES", "OPEN_CONTEMPT");
  static final List<String> NAME_GROUPS = List.of("scots-irish", "irish", "polish", "italian",
      "german", "czech", "mexican", "korean", "bosnian", "serbian", "greek", "indian", "jewish",
      "black", "swedish", "ukrainian", "lithuanian", "english");

  private final ContentLibrary lib;
  private final Settings settings;
  private final RandomSource root;
  private final List<Roster.Resident> roster;
  private final Map<String, Roster.Resident> byName = new LinkedHashMap<>();
  private final Map<String, String> handleOf = new LinkedHashMap<>();

  private SkeletonGenerator(ContentLibrary lib, Settings settings) {
    this.lib = lib;
    this.settings = settings;
    this.root = RandomSource.seeded(settings.seed()).derive("genesis");
    this.roster = lib.roster().residents();
    for (var r : roster) {
      byName.put(r.name(), r);
      handleOf.put(r.name(), r.resolvedHandle());
    }
  }

  public static WorldSeed generate(ContentLibrary lib, Settings settings) {
    return new SkeletonGenerator(lib, settings).run();
  }

  // Mutable working state, per handle.
  private final Map<String, Map<String, Integer>> traits = new LinkedHashMap<>();
  private final Map<String, List<String>> quirks = new LinkedHashMap<>();
  private final Map<String, Politics> politics = new LinkedHashMap<>();
  private final Map<String, TemperamentSeed> temperaments = new LinkedHashMap<>();
  private final Map<String, Map<String, Double>> interests = new LinkedHashMap<>();
  private final Map<String, Edge> edges = new TreeMap<>();
  private final Map<String, Double> chemistry = new TreeMap<>();
  private String jessicaPartner;

  private WorldSeed run() {
    for (var r : roster) traits.put(r.resolvedHandle(), baseTraits(r));
    outliers();
    for (var r : roster) quirks.put(r.resolvedHandle(), quirksFor(r));
    pinnedShaping();
    for (var r : roster) faithfulness(r);
    lowFaithfulness();
    politics();
    for (var r : roster) interests.put(r.resolvedHandle(), interestsFor(r));
    temperaments();
    graph();
    var arcsAndEntities = arcsAndEntities();

    var residents = new ArrayList<Resident>();
    for (var r : roster) residents.add(resident(r));
    var rels = new ArrayList<Relationship>();
    for (var e : edges.values()) rels.add(e.toSeed());
    return new WorldSeed(WorldSeed.VERSION, settings.seed(), settings.referenceDate().toString(),
        residents, rels, arcsAndEntities.arcs, arcsAndEntities.entities, null);
  }

  private RandomSource rng(String label) {
    return root.derive(label);
  }

  private static int clamp(double v) {
    return (int) Math.round(Math.max(1, Math.min(99, v)));
  }

  private static double round2(double v) {
    return Math.round(v * 100) / 100.0;
  }

  // ------------------------------------------------------------------------- traits

  private Map<String, Integer> baseTraits(Roster.Resident r) {
    var rng = rng("traits:" + r.resolvedHandle());
    var t = new LinkedHashMap<String, Integer>();
    for (String b : BIG_FIVE) t.put(b, clamp(rng.nextGaussian(50, 15)));
    for (String s : SOCIAL) t.put(s, clamp(rng.nextGaussian(50, 18)));
    return t;
  }

  /** 3–4 deliberate outliers across the cast (SPEC §6.4). */
  private void outliers() {
    var rng = rng("outliers");
    int n = 3 + rng.nextInt(2);
    var handles = new ArrayList<>(traits.keySet());
    for (int i = 0; i < n; i++) {
      String h = rng.pick(handles);
      String trait = rng.pick(BIG_FIVE);
      traits.get(h).put(trait, rng.chance(0.5) ? 3 + rng.nextInt(6) : 92 + rng.nextInt(6));
    }
  }

  private List<String> quirksFor(Roster.Resident r) {
    var rng = rng("quirks:" + r.resolvedHandle());
    var out = new ArrayList<String>(pinnedSeeds(r.name()).quirks());
    var pool = new ArrayList<String>(new java.util.TreeSet<>(lib.quirks().keySet()));
    pool.removeAll(SIGNATURE);
    int target = Math.max(out.size(), 1 + rng.nextInt(2)) + (out.isEmpty() ? 0 : rng.nextInt(2));
    target = Math.min(3, Math.max(1, target));
    while (out.size() < target && !pool.isEmpty()) {
      String q = pool.remove(rng.nextInt(pool.size()));
      if (conflicts(out, q)) continue;
      out.add(q.equals("sports_diehard") ? q + ":" + rng.pick(new ArrayList<>(new java.util.TreeSet<>(Vocab.SPORTS_TEAMS))) : q);
    }
    // apply each quirk's trait nudges
    var t = traits.get(r.resolvedHandle());
    for (String q : out) {
      var def = lib.quirks().get(q.contains(":") ? q.substring(0, q.indexOf(':')) : q);
      def.mechanics().traitNudges().forEach((trait, d) -> t.merge(trait, (int) Math.round(d), (a, b) -> clamp(a + b)));
    }
    return out;
  }

  private static boolean conflicts(List<String> have, String q) {
    for (var pair : CONFLICTS) {
      if (pair.contains(q) && have.stream().anyMatch(h -> pair.contains(h) && !h.equals(q))) return true;
    }
    return false;
  }

  private Roster.Seeds pinnedSeeds(String name) {
    return lib.roster().pinned().stream().filter(p -> p.name().equals(name)).findFirst()
        .map(p -> p.seeds() == null ? new Roster.Seeds(null, null, null) : p.seeds())
        .orElse(new Roster.Seeds(null, null, null));
  }

  private String archetype(String name) {
    return lib.roster().pinned().stream().filter(p -> p.name().equals(name)).findFirst()
        .map(Roster.Pinned::archetype).orElse(null);
  }

  /** Pinned archetypes need the traits that make them work. */
  private void pinnedShaping() {
    for (var r : roster) {
      var t = traits.get(r.resolvedHandle());
      var q = quirks.get(r.resolvedHandle());
      if (q.contains("troll")) {
        t.put("agreeableness", Math.min(t.get("agreeableness"), 30));
        t.put("humor", Math.max(t.get("humor"), 70));
        t.put("argumentativeness", Math.max(t.get("argumentativeness"), 75));
      }
      if (q.contains("serial_flirt")) t.put("flirtatiousness", Math.max(t.get("flirtatiousness"), 85));
      if (q.contains("serial_fixer_upper_buyer")) {
        t.put("impulsivity", Math.max(t.get("impulsivity"), 80));
        t.put("conscientiousness", Math.min(t.get("conscientiousness"), 35));
      }
      if (q.contains("diy_overreacher")) t.put("optimism", Math.max(t.get("optimism"), 70));
      for (String tag : pinnedSeeds(r.name()).tags()) {
        if (tag.startsWith("debate_rival")) {
          t.put("argumentativeness", Math.max(t.get("argumentativeness"), 75));
          t.put("politics_interest", 85);
        }
      }
    }
  }

  /** Around 65, partly from conscientiousness, agreeableness, impulsivity and flirtatiousness (§5.2). */
  private void faithfulness(Roster.Resident r) {
    var t = traits.get(r.resolvedHandle());
    var rng = rng("faithfulness:" + r.resolvedHandle());
    double f = 65 + 0.25 * (t.get("conscientiousness") - 50) + 0.2 * (t.get("agreeableness") - 50)
        - 0.2 * (t.get("impulsivity") - 50) - 0.25 * (t.get("flirtatiousness") - 50)
        + rng.nextGaussian(0, 8);
    t.put("faithfulness", clamp(f));
  }

  /** A few attached residents get low scores so it shows (§5.2). */
  private void lowFaithfulness() {
    var rng = rng("low-faithfulness");
    var attached = new ArrayList<String>();
    for (var r : roster) {
      boolean partnered = r.status().kind() == StatusKind.MARRIED || r.status().kind() == StatusKind.DATING;
      if (partnered && pinnedSeeds(r.name()).arcs().isEmpty()) attached.add(r.resolvedHandle());
    }
    for (int i = 0; i < 2 && !attached.isEmpty(); i++) {
      String h = attached.remove(rng.nextInt(attached.size()));
      traits.get(h).put("faithfulness", 15 + rng.nextInt(25));
    }
  }

  // ------------------------------------------------------------------------- politics & interests

  private void politics() {
    var rng = rng("politics");
    // Balanced across the spectrum: half lean left economically, half right, shuffled.
    var signs = new ArrayList<Integer>();
    for (int i = 0; i < roster.size(); i++) signs.add(i % 2 == 0 ? 1 : -1);
    for (int i = signs.size() - 1; i > 0; i--) {
      int j = rng.nextInt(i + 1);
      var tmp = signs.get(i);
      signs.set(i, signs.get(j));
      signs.set(j, tmp);
    }
    for (int i = 0; i < roster.size(); i++) {
      var r = roster.get(i);
      String h = r.resolvedHandle();
      double economic = signs.get(i) * rng.nextDouble(0.1, 0.9);
      double social = Math.max(-1, Math.min(1, economic * 0.6 + rng.nextGaussian(0, 0.35)));
      double u = rng.nextDouble();
      double interest = u * u; // most residents have low interest — realistic
      var q = quirks.get(h);
      if (q.contains("debate_lover") || q.contains("doomscroller")) interest = Math.max(interest, 0.7);
      politics.put(h, new Politics(round2(economic), round2(social), round2(interest), Map.of()));
    }
    // Sarah and Tim argue economics; which side each takes is genesis's call (§6.3).
    var rivals = new ArrayList<String[]>();
    for (var p : lib.roster().pinned()) {
      for (String tag : pinnedSeeds(p.name()).tags()) {
        if (tag.startsWith("debate_rival")) {
          rivals.add(new String[] {handleOf.get(p.name()), handleOf.get(tag.substring(tag.indexOf("->") + 2).strip())});
        }
      }
    }
    var done = new HashSet<String>();
    for (var pair : rivals) {
      String key = pair[0].compareTo(pair[1]) < 0 ? pair[0] + "|" + pair[1] : pair[1] + "|" + pair[0];
      if (!done.add(key)) continue;
      boolean firstLeft = rng.chance(0.5);
      politics.put(pair[0], new Politics(firstLeft ? -0.75 : 0.65, firstLeft ? -0.5 : 0.15, 0.9, Map.of("socialism", firstLeft ? 0.6 : -0.8)));
      politics.put(pair[1], new Politics(firstLeft ? 0.65 : -0.75, firstLeft ? 0.15 : -0.5, 0.9, Map.of("socialism", firstLeft ? -0.8 : 0.6)));
    }
    for (var e : politics.entrySet()) traits.get(e.getKey()).put("politics_interest", clamp(e.getValue().interest() * 100));
  }

  private Map<String, Double> interestsFor(Roster.Resident r) {
    String h = r.resolvedHandle();
    var rng = rng("interests:" + h);
    var out = new TreeMap<String, Double>();
    var topics = new ArrayList<>(new java.util.TreeSet<>(Vocab.TOPICS));
    topics.remove("faith");
    int n = 4 + rng.nextInt(4);
    for (int i = 0; i < n; i++) out.put(topics.remove(rng.nextInt(topics.size())), round2(rng.nextDouble(0.3, 1.0)));
    for (String q : quirks.get(h)) {
      switch (q.contains(":") ? q.substring(0, q.indexOf(':')) : q) {
        case "gym_rat" -> out.put("fitness", 0.9);
        case "foodie_photographer" -> out.put("food", 0.95);
        case "diy_overreacher" -> out.put("home_improvement", 0.95);
        case "pet_obsessed" -> out.put("pets", 0.95);
        case "gadget_early_adopter" -> out.put("tech", 0.9);
        case "plant_parent" -> out.put("outdoors", Math.max(out.getOrDefault("outdoors", 0.0), 0.6));
        case "serial_fixer_upper_buyer" -> out.put("cars", 0.95);
        case "sports_diehard" -> out.put("sports:" + q.substring(q.indexOf(':') + 1), 0.95);
        case "doomscroller", "debate_lover" -> out.put("politics", Math.max(out.getOrDefault("politics", 0.0), 0.8));
        default -> { }
      }
    }
    if (politics.get(h).interest() >= 0.6) out.put("politics", Math.max(out.getOrDefault("politics", 0.0), politics.get(h).interest()));
    var obs = r.faith() == null ? Observance.NONE : r.faith().observance();
    if (obs == Observance.PRACTICING || obs == Observance.DEVOUT) {
      out.put("faith", obs == Observance.DEVOUT ? 0.9 : 0.6);
    }
    return out;
  }

  // ------------------------------------------------------------------------- temperament

  private void temperaments() {
    var rng = rng("temperament");
    var handles = new ArrayList<>(traits.keySet());
    Comparator<String> warmth = Comparator.comparingDouble(h -> {
      var t = traits.get(h);
      double score = t.get("agreeableness") + t.get("optimism");
      var q = quirks.get(h);
      if (q.contains("chronic_complainer")) score -= 40;
      if (q.contains("wholesome_cheerleader")) score += 40;
      return score + rng.derive(h).nextDouble() * 0.001; // ties broken deterministically
    });
    handles.sort(warmth);
    var grouches = new HashSet<>(handles.subList(0, settings.grouches()));
    var sweethearts = new HashSet<>(handles.subList(handles.size() - settings.sweethearts(), handles.size()));
    var rules = lib.temperament();
    for (String h : traits.keySet()) {
      var preset = grouches.contains(h) ? Temperament.GROUCH
          : sweethearts.contains(h) ? Temperament.SWEETHEART : Temperament.TYPICAL;
      var p = rules.presets().get(preset);
      var j = rng.derive("jitter:" + h);
      double jit = rules.jitter();
      temperaments.put(h, new TemperamentSeed(preset,
          round2(p.baseline() * (1 + j.nextDouble(-jit, jit))),
          round2(p.goodGain() * (1 + j.nextDouble(-jit, jit))),
          round2(p.badGain() * (1 + j.nextDouble(-jit, jit))),
          p.floor(), p.ceiling()));
    }
  }

  // ------------------------------------------------------------------------- the graph

  /** One direction of a tie while it's being built. */
  final class Edge {
    final String from;
    final String to;
    String kind;
    double affinity;
    double trust;
    double attraction;
    double familiarity;
    double respect;
    final List<String> tags = new ArrayList<>();
    boolean connected;

    Edge(String from, String to) {
      this.from = from;
      this.to = to;
    }

    Relationship toSeed() {
      var tagsSorted = new ArrayList<>(tags);
      java.util.Collections.sort(tagsSorted);
      return new Relationship(from, to, kind, round2(affinity), round2(trust), round2(attraction),
          round2(familiarity), round2(respect), chem(from, to), tagsSorted, connected, null);
    }
  }

  private static String pairKey(String a, String b) {
    return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
  }

  private double chem(String a, String b) {
    return chemistry.computeIfAbsent(pairKey(a, b),
        k -> round2(Math.max(-1, Math.min(1, rng("chemistry:" + k).nextGaussian(0, 0.4)))));
  }

  private Edge edge(String from, String to) {
    return edges.computeIfAbsent(from + "->" + to, k -> new Edge(from, to));
  }

  private boolean hasTie(String a, String b) {
    return edges.containsKey(a + "->" + b);
  }

  private Gender gender(String h) {
    return roster.stream().filter(r -> r.resolvedHandle().equals(h)).findFirst().orElseThrow().gender();
  }

  /** Sets both directions of a tie to {@code kind}, with per-direction values around its band. */
  private void tie(String a, String b, String kind, boolean connected) {
    var rng = rng("tie:" + pairKey(a, b) + ":" + kind);
    double fam = band(kind, "fam", rng);
    for (var pair : List.of(new String[] {a, b}, new String[] {b, a})) {
      var e = edge(pair[0], pair[1]);
      var t = temperaments.get(pair[0]);
      e.kind = kind;
      // An acquaintance stays below the meaningful-tie threshold (familiarity 40).
      e.familiarity = clampRange(fam + rng.nextGaussian(0, 4), 0, kind.equals("ACQUAINTANCE") ? 38 : 100);
      double aff = band(kind, "aff", rng) + t.baseline() * 0.5 + chem(a, b) * 12 + rng.nextGaussian(0, 5);
      e.affinity = clampRange(aff, t.floor(), t.ceiling());
      e.trust = clampRange(band(kind, "trust", rng) + rng.nextGaussian(0, 6), 0, 100);
      e.respect = clampRange(rng.nextGaussian(15, 18) + chem(a, b) * 10, -100, 100);
      boolean opposite = gender(pair[0]) != gender(pair[1]);
      e.attraction = !opposite ? 0
          : kind.equals("PARTNER") ? clampRange(rng.nextGaussian(78, 8), 0, 100)
          : kind.equals("FAMILY") ? 0
          : clampRange(rng.nextGaussian(12, 8) + chem(a, b) * 15, 0, 100);
      e.connected = connected;
    }
  }

  private static double clampRange(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static double band(String kind, String dim, RandomSource rng) {
    double[] r = switch (kind + ":" + dim) {
      case "PARTNER:fam" -> new double[] {90, 98};
      case "PARTNER:aff" -> new double[] {60, 80};
      case "PARTNER:trust" -> new double[] {70, 90};
      case "FAMILY:fam" -> new double[] {70, 95};
      case "FAMILY:aff" -> new double[] {30, 60};
      case "FAMILY:trust" -> new double[] {60, 85};
      case "CLOSE_FRIEND:fam" -> new double[] {75, 95};
      case "CLOSE_FRIEND:aff" -> new double[] {55, 75};
      case "CLOSE_FRIEND:trust" -> new double[] {70, 90};
      case "FRIEND:fam" -> new double[] {45, 75};
      case "FRIEND:aff" -> new double[] {25, 50};
      case "FRIEND:trust" -> new double[] {45, 70};
      case "COWORKER:fam" -> new double[] {40, 70};
      case "COWORKER:aff" -> new double[] {5, 30};
      case "COWORKER:trust" -> new double[] {35, 60};
      case "EX:fam" -> new double[] {60, 85};
      case "EX:aff" -> new double[] {-20, 10};
      case "EX:trust" -> new double[] {15, 40};
      case "RIVAL:fam" -> new double[] {50, 70};
      case "RIVAL:aff" -> new double[] {-45, -30};
      case "RIVAL:trust" -> new double[] {5, 25};
      case "ACQUAINTANCE:fam" -> new double[] {10, 35};
      case "ACQUAINTANCE:aff" -> new double[] {0, 15};
      default -> new double[] {20, 40};
    };
    return rng.nextDouble(r[0], r[1]);
  }

  private List<String> members(String circle) {
    return roster.stream().filter(r -> r.circles().contains(circle)).map(Roster.Resident::resolvedHandle).toList();
  }

  private void graph() {
    var rng = rng("graph");
    // Workplaces: coworkers. U of I: old friends. The block: neighbors. Softball: a few friends each.
    for (String c : List.of("lakeshore", "prairie-path", "kinzie")) {
      var m = members(c);
      for (int i = 0; i < m.size(); i++) for (int j = i + 1; j < m.size(); j++) tie(m.get(i), m.get(j), "COWORKER", true);
    }
    var uiuc = members("uiuc");
    for (int i = 0; i < uiuc.size(); i++) {
      for (int j = i + 1; j < uiuc.size(); j++) tie(uiuc.get(i), uiuc.get(j), rng.chance(0.75) ? "FRIEND" : "ACQUAINTANCE", true);
    }
    var block = members("berwyn-block");
    for (int i = 0; i < block.size(); i++) {
      for (int j = i + 1; j < block.size(); j++) tie(block.get(i), block.get(j), rng.chance(0.5) ? "FRIEND" : "ACQUAINTANCE", rng.chance(0.8));
    }
    var softball = members("softball");
    for (int i = 0; i < softball.size(); i++) {
      for (int j = i + 1; j < softball.size(); j++) {
        if (!rng.chance(0.6)) continue; // a ten-person team doesn't all know each other equally
        tie(softball.get(i), softball.get(j), rng.chance(0.45) ? "FRIEND" : "ACQUAINTANCE", true);
      }
    }
    // Family and couples, from the roster.
    for (var r : roster) {
      for (var f : r.family()) {
        String other = handleOf.get(f.with());
        if (r.resolvedHandle().compareTo(other) < 0) tie(r.resolvedHandle(), other, "FAMILY", true);
      }
      if (r.status().with() != null) {
        String other = handleOf.get(r.status().with());
        if (r.resolvedHandle().compareTo(other) < 0) tie(r.resolvedHandle(), other, "PARTNER", true);
      }
      for (var k : r.knows()) {
        String other = handleOf.get(k.with());
        if (!hasTie(r.resolvedHandle(), other)) tie(r.resolvedHandle(), other, "ACQUAINTANCE", true);
      }
    }
    // The online circle: people who know each other only through Radiance.
    var online = members("online");
    for (String h : online) {
      int n = 1 + rng.nextInt(3);
      for (int k = 0; k < n; k++) {
        String other = rng.chance(0.7) ? rng.pick(online) : rng.pick(new ArrayList<>(traits.keySet()));
        if (!other.equals(h) && !hasTie(h, other)) tie(h, other, "ACQUAINTANCE", true);
      }
    }
    // A few bridges between circles.
    var all = new ArrayList<>(traits.keySet());
    for (int k = 0; k < 4; k++) {
      String a = rng.pick(all);
      String b = rng.pick(all);
      if (!a.equals(b) && !hasTie(a, b)) tie(a, b, "FRIEND", true);
    }
    specialTies(rng);
    balanceMeaningfulTies(rng);
    crushes(rng);
  }

  private void specialTies(RandomSource rng) {
    // Best friends (§5.3): Jessica and Megan.
    String jessica = handleOf.get("Jessica Vogel");
    String megan = handleOf.get("Megan Quinlan");
    if (jessica != null && megan != null) {
      tie(jessica, megan, "CLOSE_FRIEND", true);
      edge(jessica, megan).tags.add("best_friend");
      edge(megan, jessica).tags.add("best_friend");
    }
    // A pinned relationship "showing cracks" (§6.3) shows it on the edge, not just in satisfaction.
    for (var p : lib.roster().pinned()) {
      var r = byName.get(p.name());
      if (p.archetype() != null && p.archetype().toLowerCase().contains("cracks") && r.status().with() != null) {
        var e = edge(handleOf.get(p.name()), handleOf.get(r.status().with()));
        e.affinity = 38 + rng.nextInt(8);
        e.trust = 45 + rng.nextInt(10);
      }
    }
    // Pinned debate rivals.
    for (var p : lib.roster().pinned()) {
      for (String tag : pinnedSeeds(p.name()).tags()) {
        String from = handleOf.get(p.name());
        String to = handleOf.get(tag.substring(tag.indexOf("->") + 2).strip());
        String label = tag.substring(0, tag.indexOf("->")).strip();
        if (!hasTie(from, to)) tie(from, to, "FRIEND", true);
        if (!edge(from, to).tags.contains(label)) edge(from, to).tags.add(label);
      }
    }
    var singles = roster.stream()
        .filter(r -> r.status().kind() == StatusKind.SINGLE || r.status().kind() == StatusKind.DIVORCED)
        .map(Roster.Resident::resolvedHandle).toList();
    var men = singles.stream().filter(h -> gender(h) == Gender.M).toList();
    var women = singles.stream().filter(h -> gender(h) == Gender.F).toList();

    // Jessica's secret partner: one of the roster's single men (§6.3).
    var used = new HashSet<String>();
    if (jessica != null) {
      var candidates = roster.stream().filter(r -> r.gender() == Gender.M && r.status().kind() == StatusKind.SINGLE)
          .map(Roster.Resident::resolvedHandle).filter(h -> !isFamily(jessica, h)).toList();
      jessicaPartner = rng.pick(candidates);
      used.add(jessica);
      used.add(jessicaPartner);
      tie(jessica, jessicaPartner, "FRIEND", true);
      protectedPairs.add(pairKey(jessica, jessicaPartner)); // balancing must not undo the secret couple
      for (var pair : List.of(new String[] {jessica, jessicaPartner}, new String[] {jessicaPartner, jessica})) {
        var e = edge(pair[0], pair[1]);
        e.familiarity = 80;
        e.affinity = 70;
        e.attraction = 82;
        e.trust = 70;
      }
      // Megan isn't told (decision 6); she notices the bailed plans.
      if (megan != null) edge(megan, jessica).tags.add("suspects");
    }

    // Two exes inside the network (a man and a woman each).
    int exes = 0;
    for (int tries = 0; tries < 200 && exes < 2; tries++) {
      String m = rng.pick(men);
      String w = rng.pick(women);
      if (used.contains(m) || used.contains(w) || isFamily(m, w)) continue;
      tie(m, w, "EX", true);
      if (rng.chance(0.5)) edge(w, m).tags.add("avoiding");
      used.add(m);
      used.add(w);
      exes++;
    }
    // Two or three more debate rivalries: opposite politics, both argumentative enough.
    int rivals = 1 + rng.nextInt(2);
    var all = new ArrayList<>(traits.keySet());
    for (int bar : new int[] {50, 35, 0}) { // relax the argumentativeness bar until someone qualifies
      var candidates = new ArrayList<String[]>();
      for (var e : edges.values()) {
        if (e.from.compareTo(e.to) >= 0 || e.tags.stream().anyMatch(t -> t.startsWith("debate_rival"))) continue;
        if (Math.signum(politics.get(e.from).economic()) == Math.signum(politics.get(e.to).economic())) continue;
        if (traits.get(e.from).get("argumentativeness") < bar || traits.get(e.to).get("argumentativeness") < bar) continue;
        candidates.add(new String[] {e.from, e.to});
      }
      while (rivals > 0 && !candidates.isEmpty()) {
        var pair = candidates.remove(rng.nextInt(candidates.size()));
        String topic = rng.pick(List.of("politics", "immigration", "religion", "sports", "economics"));
        edge(pair[0], pair[1]).tags.add("debate_rival:" + topic);
        edge(pair[1], pair[0]).tags.add("debate_rival:" + topic);
        rivals--;
      }
      if (rivals == 0) break;
    }
    // One real feud.
    for (int tries = 0; tries < 300; tries++) {
      String a = rng.pick(all);
      String b = rng.pick(all);
      if (a.equals(b) || !hasTie(a, b) || isFamily(a, b) || edge(a, b).kind.equals("PARTNER")
          || edge(a, b).kind.equals("CLOSE_FRIEND") || used.contains(a) || used.contains(b)) continue;
      tie(a, b, "RIVAL", true);
      edge(a, b).tags.add("feud");
      edge(b, a).tags.add("feud");
      feud = new String[] {a, b};
      break;
    }
  }

  /**
   * Two or three one-sided crushes, always between a man and a woman. Placed after balancing so no
   * later re-roll of the tie can erase the attraction.
   */
  private void crushes(RandomSource rng) {
    String jessica = handleOf.get("Jessica Vogel");
    var all = new ArrayList<>(traits.keySet());
    var singles = roster.stream()
        .filter(r -> r.status().kind() == StatusKind.SINGLE || r.status().kind() == StatusKind.DIVORCED)
        .map(Roster.Resident::resolvedHandle).toList();
    int crushes = 2 + rng.nextInt(2);
    for (int tries = 0; tries < 300 && crushes > 0; tries++) {
      String a = rng.pick(all);
      String b = rng.pick(all);
      if (a.equals(b) || gender(a) == gender(b) || isFamily(a, b) || a.equals(jessicaPartner) || a.equals(jessica)) continue;
      if (hasTie(a, b) && (edge(a, b).kind.equals("PARTNER") || edge(a, b).kind.equals("EX"))) continue;
      boolean free = singles.contains(a) || traits.get(a).get("faithfulness") < 50;
      if (!free || (hasTie(a, b) && edge(a, b).tags.contains("crush"))) continue;
      if (!hasTie(a, b)) tie(a, b, "ACQUAINTANCE", true);
      var e = edge(a, b);
      e.tags.add("crush");
      e.attraction = 65 + rng.nextInt(20);
      e.affinity = Math.max(e.affinity, 40);
      crushes--;
    }
  }

  private String[] feud;
  private final Set<String> protectedPairs = new HashSet<>();

  private boolean isFamily(String a, String b) {
    return hasTie(a, b) && "FAMILY".equals(edge(a, b).kind);
  }

  /**
   * 5–12 meaningful ties each (§5.3). Two passes, so fixing one person can't break another: first
   * demote the busiest down to 12, then deepen ties for the loneliest, only toward people under 12.
   */
  private void balanceMeaningfulTies(RandomSource rng) {
    for (String h : traits.keySet()) {
      var meaningful = edges.values().stream().filter(e -> e.from.equals(h)).filter(this::meaningful).toList();
      if (meaningful.size() <= 12) continue;
      var demote = new ArrayList<>(meaningful.stream()
          .filter(e -> e.kind.equals("FRIEND") || e.kind.equals("COWORKER") || e.kind.equals("ACQUAINTANCE"))
          .filter(e -> e.tags.isEmpty() && !protectedPairs.contains(pairKey(e.from, e.to)))
          .sorted(Comparator.comparingDouble((Edge e) -> e.familiarity).thenComparing(e -> e.to))
          .toList());
      for (int i = 0; i < meaningful.size() - 12 && i < demote.size(); i++) {
        tie(demote.get(i).from, demote.get(i).to, "ACQUAINTANCE", true);
      }
    }
    var all = new ArrayList<>(traits.keySet());
    for (String h : traits.keySet()) {
      int have = meaningfulCount(h);
      // Prefer deepening an existing acquaintance to inventing a new tie.
      var shallow = new ArrayList<>(edges.values().stream()
          .filter(e -> e.from.equals(h) && !meaningful(e) && e.tags.isEmpty())
          .sorted(Comparator.comparing((Edge e) -> -chem(e.from, e.to)).thenComparing(e -> e.to))
          .toList());
      for (int i = 0; i < shallow.size() && have < 5; i++) {
        String other = shallow.get(i).to;
        if (meaningfulCount(other) >= 12) continue;
        tie(h, other, "FRIEND", true);
        have++;
      }
      for (int tries = 0; tries < 200 && have < 5; tries++) {
        String other = rng.pick(all);
        if (other.equals(h) || meaningfulCount(other) >= 12) continue;
        if (hasTie(h, other) && (meaningful(edge(h, other)) || !edge(h, other).tags.isEmpty())) continue;
        tie(h, other, "FRIEND", true);
        have++;
      }
    }
  }

  private int meaningfulCount(String h) {
    return (int) edges.values().stream().filter(e -> e.from.equals(h)).filter(this::meaningful).count();
  }

  private boolean meaningful(Edge e) {
    return switch (e.kind) {
      case "FRIEND", "CLOSE_FRIEND", "FAMILY", "PARTNER", "EX", "RIVAL" -> true;
      default -> e.familiarity >= 40;
    };
  }

  // ------------------------------------------------------------------------- arcs & entities

  private record ArcsAndEntities(List<Arc> arcs, List<Entity> entities) {}

  private ArcsAndEntities arcsAndEntities() {
    var rng = rng("arcs");
    var arcs = new ArrayList<Arc>();
    var entities = new ArrayList<Entity>();
    var inArc = new HashMap<String, Set<String>>();

    // Pinned arc seeds.
    for (var p : lib.roster().pinned()) {
      String h = handleOf.get(p.name());
      for (String seed : pinnedSeeds(p.name()).arcs()) {
        String defId = seed.substring(0, seed.indexOf(':'));
        String phase = seed.substring(seed.indexOf(':') + 1);
        var roles = new LinkedHashMap<String, String>();
        var data = new LinkedHashMap<String, Object>();
        var knowers = new ArrayList<String>();
        switch (defId) {
          case "romance" -> {
            var r = byName.get(p.name());
            String partner = r.status().with() != null ? handleOf.get(r.status().with()) : jessicaPartner;
            roles.put("a", h);
            roles.put("b", partner);
            data.put("partner", partner);
            if (visibility(defId, phase) != Visibility.PUBLIC) {
              knowers.add(h);
              knowers.add(partner); // the couple only (decision 6)
            }
          }
          case "home_project" -> {
            roles.put("owner", h);
            var home = new Entity("home:" + h, h, EntityKind.HOME, possessive(byName.get(p.name())) + " bungalow",
                "a 1920s Chicago brick bungalow on a narrow Berwyn lot, its kitchen gutted to the studs",
                rng.nextLong() & 0xFFFFFFFL, sortedMap(Map.of("rooms", 5, "built", 1926)), "OWNED");
            entities.add(home);
            data.put("entity", home.key());
            data.put("room", "kitchen");
          }
          default -> roles.put(firstRole(defId), h);
        }
        arcs.add(arc(defId, phase, roles, knowers, data, rng));
        roles.values().forEach(x -> inArc.computeIfAbsent(x, k -> new HashSet<>()).add(defId));
      }
    }
    // Jesse's car saga: his quirk all but guarantees one is running.
    for (var r : roster) {
      String h = r.resolvedHandle();
      if (quirks.get(h).contains("serial_fixer_upper_buyer")) {
        var car = rng.pick(lib.table("fixer_upper_cars"));
        String desc = car.get("color") + " " + car.get("year") + " " + car.get("make") + " " + car.get("model")
            + " with " + car.get("flaw");
        var entity = new Entity("car:" + h, h, EntityKind.CAR, "the " + car.get("make") + " " + car.get("model"),
            "a " + desc, rng.nextLong() & 0xFFFFFFFL, sortedMap(car), "OWNED");
        entities.add(entity);
        arcs.add(arc("car_saga", "honeymoon", Map.of("owner", h), List.of(), Map.of("entity", entity.key()), rng));
        inArc.computeIfAbsent(h, k -> new HashSet<>()).add("car_saga");
      }
    }
    // The real feud is out in the open.
    if (feud != null) {
      arcs.add(arc("feud", "cold_war", sortedMap(Map.of("a", feud[0], "b", feud[1])), List.of(), Map.of(), rng));
      inArc.computeIfAbsent(feud[0], k -> new HashSet<>()).add("feud");
      inArc.computeIfAbsent(feud[1], k -> new HashSet<>()).add("feud");
    }
    // 3–5 others mid-arc (§6.4), on single-role arcs anyone can carry.
    var extras = new ArrayList<>(List.of("training_goal", "new_hobby", "job_search", "side_hustle", "school", "new_pet"));
    extras.removeIf(lib::isBlacklisted);
    int n = 3 + rng.nextInt(3) - (arcs.size() >= 6 ? 1 : 0);
    var all = new ArrayList<>(traits.keySet());
    for (int tries = 0; tries < 200 && n > 0 && !extras.isEmpty(); tries++) {
      String defId = rng.pick(extras);
      String h = rng.pick(all);
      var busy = inArc.getOrDefault(h, Set.of());
      var def = lib.arcs().get(defId);
      if (busy.size() >= 2 || busy.contains(defId) || def.exclusiveWith().stream().anyMatch(busy::contains)) continue;
      var phases = def.phases().stream().filter(ph -> !ph.terminal()).toList();
      String phase = phases.get(rng.nextInt(Math.min(2, phases.size()))).id();
      arcs.add(arc(defId, phase, Map.of(firstRole(defId), h), List.of(), Map.of(), rng));
      inArc.computeIfAbsent(h, k -> new HashSet<>()).add(defId);
      extras.remove(defId);
      n--;
    }
    // Pets: the pet people first, then a few others.
    var petPeople = new ArrayList<String>();
    for (var r : roster) {
      String h = r.resolvedHandle();
      if (quirks.get(h).contains("pet_obsessed") || r.work().title().toLowerCase().contains("veterinarian")) petPeople.add(h);
    }
    int extraPets = 3 + rng.nextInt(3);
    for (int i = 0; i < extraPets; i++) {
      String h = rng.pick(all);
      if (!petPeople.contains(h)) petPeople.add(h);
    }
    for (String h : petPeople) {
      var pet = rng.pick(lib.table("shelter_pets"));
      String species = String.valueOf(pet.get("species"));
      var names = lib.table("pet_names").stream().filter(t -> species.equals(t.get("species"))).findFirst().orElseThrow();
      @SuppressWarnings("unchecked")
      String name = rng.pick((List<String>) names.get("names"));
      entities.add(new Entity("pet:" + h, h, EntityKind.PET, name, String.valueOf(pet.get("look")),
          rng.nextLong() & 0xFFFFFFFL, sortedMap(Map.of("species", species, "breed", pet.get("breed"))), "OWNED"));
    }
    arcs.sort(Comparator.comparing(Arc::key));
    entities.sort(Comparator.comparing(Entity::key));
    return new ArcsAndEntities(arcs, entities);
  }

  private Arc arc(String defId, String phase, Map<String, String> roles, List<String> knowers,
      Map<String, Object> data, RandomSource rng) {
    var def = lib.arcs().get(defId);
    var ph = def.phases().stream().filter(p -> p.id().equals(phase)).findFirst().orElseThrow();
    int days = ph.durationDays().size() == 2
        ? ph.durationDays().get(0) + rng.nextInt(Math.max(1, (ph.durationDays().get(1) - ph.durationDays().get(0)) / 2 + 1))
        : 0;
    if (defId.equals("romance") && phase.equals("secret_dating")) days = 30; // "for a month" (§6.3)
    String key = "arc:" + defId + ":" + String.join("+", new TreeMap<>(roles).values());
    return new Arc(key, defId, phase, visibility(defId, phase), sortedMap(roles), List.copyOf(knowers), days,
        sortedMap(data));
  }

  /** Def maps are unordered; the first role by name keeps generation deterministic. */
  private String firstRole(String defId) {
    return new java.util.TreeSet<>(lib.arcs().get(defId).roles().keySet()).first();
  }

  private Visibility visibility(String defId, String phase) {
    var v = lib.arcs().get(defId).phases().stream().filter(p -> p.id().equals(phase)).findFirst()
        .map(app.radiance.content.Defs.Phase::visibility).orElse(null);
    return v == null ? Visibility.PUBLIC : v;
  }

  private static <V> Map<String, V> sortedMap(Map<String, V> m) {
    return new TreeMap<>(m);
  }

  private static String possessive(Roster.Resident r) {
    String first = r.firstName();
    return first + (first.endsWith("s") ? "'" : "'s");
  }

  // ------------------------------------------------------------------------- residents

  private Resident resident(Roster.Resident r) {
    String h = r.resolvedHandle();
    var rng = rng("person:" + h);
    var t = traits.get(h);
    LocalDate birth = settings.referenceDate().minusYears(r.age()).minusDays(1 + rng.nextInt(364));
    String partner = r.status().with() != null ? handleOf.get(r.status().with()) : null;
    return new Resident(h, r.name(), r.displayName(), r.gender(), birth.toString(), r.heritage(),
        r.faith() == null ? null : r.faith().tradition(),
        r.faith() == null ? Observance.NONE : r.faith().observance(),
        r.livesIn(), r.work().title(), r.work().employer(), r.status().kind(), partner, r.circles(),
        archetype(r.name()) != null, archetype(r.name()), new TreeMap<>(t), quirks.get(h),
        interests.get(h), politics.get(h), biases(r, rng), taste(rng), temperaments.get(h),
        routine(r, rng), initialState(r, rng), offNetwork(r, rng),
        null, null, null, null, null, null, null);
  }

  private List<Bias> biases(Roster.Resident r, RandomSource rng) {
    var out = new ArrayList<Bias>();
    var targets = new ArrayList<>(EVERYDAY_BIAS_TARGETS);
    int n = 2 + rng.nextInt(2);
    for (int i = 0; i < n; i++) {
      out.add(new Bias(targets.remove(rng.nextInt(targets.size())), round2(rng.nextDouble(0.15, 0.5)), "JOKES", false));
    }
    if (strongPrejudice().contains(r.resolvedHandle())) {
      out.add(new Bias(rng.pick(STRONG_BIAS_TARGETS), round2(rng.nextDouble(0.6, 0.9)), rng.pick(STYLES), true));
    }
    return out;
  }

  private Set<String> strongPrejudiceCache;

  /** Chosen without regard to anyone's own background (§5.2). */
  private Set<String> strongPrejudice() {
    if (strongPrejudiceCache == null) {
      var rng = rng("prejudice");
      var all = new ArrayList<>(traits.keySet());
      var chosen = new java.util.TreeSet<String>();
      while (chosen.size() < Math.min(settings.strongPrejudice(), all.size())) chosen.add(rng.pick(all));
      strongPrejudiceCache = chosen;
    }
    return strongPrejudiceCache;
  }

  private Map<String, Double> taste(RandomSource rng) {
    var out = new LinkedHashMap<String, Double>();
    for (String f : TASTE) {
      double mean = switch (f) {
        case "pretentiousness", "bragging", "preachiness" -> -0.4;
        case "humor", "warmth" -> 0.4;
        case "political_alignment" -> 0.5;
        default -> 0.1;
      };
      out.put(f, round2(Math.max(-1, Math.min(1, rng.nextGaussian(mean, 0.3)))));
    }
    return out;
  }

  private Routine routine(Roster.Resident r, RandomSource rng) {
    String job = r.work().title().toLowerCase();
    String schedule = job.contains("nurse") || job.contains("respiratory") ? "hospital_12h_shifts"
        : job.contains("firefighter") ? "fire_24_on_48_off"
        : job.contains("retired") ? "retired"
        : job.contains("stay-at-home") ? "stay_at_home_parent"
        : job.contains("student") ? "student"
        : job.contains("teacher") ? "school_day"
        : job.contains("line cook") || job.contains("brewer") ? "evenings_and_weekends"
        : job.contains("realtor") || job.contains("electrician") || job.contains("loan officer") ? "self_directed"
        : job.contains("veterinarian") ? "clinic_days"
        : "office_9_to_5";
    String chronotype = rng.pick(List.of("early", "normal", "normal", "late"));
    var weekday = new ArrayList<Double>();
    var weekend = new ArrayList<Double>();
    for (int hour = 0; hour < 24; hour++) {
      weekday.add(round2(intensity(schedule, chronotype, hour, false) * rng.nextDouble(0.85, 1.15)));
      weekend.add(round2(intensity(schedule, chronotype, hour, true) * rng.nextDouble(0.85, 1.15)));
    }
    var q = quirks.get(r.resolvedHandle());
    double posts = Math.exp(rng.nextGaussian(Math.log(0.6), 0.8));
    if (q.contains("lurker")) posts = 0.03 + rng.nextDouble() * 0.05;
    if (q.contains("oversharer")) posts = 2.5 + rng.nextDouble();
    var t = traits.get(r.resolvedHandle());
    int budget = (int) Math.round(10 + t.get("extraversion") * 0.4 + rng.nextDouble(0, 15));
    String weekendPattern = schedule.equals("evenings_and_weekends") || job.contains("bartends") ? "works_weekends"
        : rng.pick(List.of("errands_and_family", "social", "homebody", "active"));
    return new Routine(chronotype, schedule, weekendPattern, weekday, weekend, round2(Math.min(posts, 4.0)), budget);
  }

  /** A simple online-intensity curve: phone checks around commutes, lunch and evenings. */
  private static double intensity(String schedule, String chronotype, int hour, boolean weekend) {
    int shift = chronotype.equals("early") ? -1 : chronotype.equals("late") ? 2 : 0;
    int h = Math.floorMod(hour - shift, 24);
    if (h < 6) return h == 0 ? 0.15 : 0.03;
    double v = switch (h) {
      case 6, 7 -> 0.35;
      case 8 -> 0.3;
      case 12 -> 0.5;
      case 17, 18 -> 0.45;
      case 19, 20, 21 -> 0.7;
      case 22 -> 0.5;
      case 23 -> 0.3;
      default -> 0.2;
    };
    if (weekend) v = h >= 9 && h <= 22 ? Math.max(v, 0.45) : v;
    if (!weekend && (schedule.equals("retired") || schedule.equals("stay_at_home_parent"))) v = Math.max(v, 0.4);
    if (schedule.equals("evenings_and_weekends") && h >= 16 && h <= 23) v *= 0.4;
    return Math.min(1.0, v);
  }

  private InitialState initialState(Roster.Resident r, RandomSource rng) {
    boolean partnered = r.status().kind() == StatusKind.MARRIED || r.status().kind() == StatusKind.DATING;
    Double satisfaction = partnered ? round2(Math.max(0.1, Math.min(0.95, rng.nextGaussian(0.68, 0.12)))) : null;
    // Sarah and Ryan's relationship is showing cracks (§6.3).
    if (partnered && r.status().with() != null && archetype(r.name()) != null
        && archetype(r.name()).toLowerCase().contains("cracks")) satisfaction = 0.38;
    return new InitialState(round2(rng.nextGaussian(0.1, 0.2)), round2(rng.nextDouble(0.5, 0.85)),
        round2(rng.nextDouble(0.15, 0.45)), satisfaction);
  }

  // ------------------------------------------------------------------------- off-network people

  /** Spouses, kids, an ex-spouse or a late spouse, named by the engine (§5.5); text only. */
  private List<OffNetworkPerson> offNetwork(Roster.Resident r, RandomSource rng) {
    var out = new ArrayList<OffNetworkPerson>();
    String last = r.name().substring(r.name().lastIndexOf(' ') + 1);
    Gender other = r.gender() == Gender.M ? Gender.F : Gender.M;
    String group = nameGroup(r.heritage(), rng);
    var kind = r.status().kind();
    if (kind == StatusKind.MARRIED && r.status().with() == null) {
      String role = other == Gender.F ? "wife" : "husband";
      out.add(new OffNetworkPerson(adultName(group, other, rng) + " " + last, role,
          r.age() + rng.nextInt(7) - 3, null));
    }
    if (kind == StatusKind.DIVORCED) {
      out.add(new OffNetworkPerson(adultName(group, other, rng), other == Gender.F ? "ex-wife" : "ex-husband", null, null));
    }
    if (kind == StatusKind.WIDOWED) {
      out.add(new OffNetworkPerson(adultName(group, other, rng) + " " + last,
          other == Gender.F ? "late wife" : "late husband", null, "passed away a few years ago"));
    }
    String notes = r.notes() == null ? "" : r.notes().toLowerCase();
    for (int age : kidAges(r.notes(), r.age(), rng)) {
      Gender g = notes.matches(".*\\bsons?\\b.*") ? Gender.M
          : notes.matches(".*\\bdaughters?\\b.*") ? Gender.F
          : rng.chance(0.5) ? Gender.M : Gender.F;
      out.add(new OffNetworkPerson(kidName(age, g, rng), g == Gender.M ? "son" : "daughter", age, null));
    }
    return out;
  }

  private static List<Integer> kidAges(String notes, int parentAge, RandomSource rng) {
    if (notes == null) return List.of();
    String n = notes.toLowerCase();
    int count = n.contains("twins") ? 2 : n.contains("four") ? 4 : n.contains("three") ? 3
        : n.contains("two") ? 2 : (n.contains("one") ? 1 : 0);
    int min = 1;
    int max = Math.max(2, parentAge - 24);
    if (n.contains("toddler")) { min = 1; max = 3; }
    else if (n.contains("teen")) { min = 13; max = 18; }
    else if (n.contains("grown")) { min = 22; max = Math.max(23, parentAge - 22); }
    else if (n.contains("college")) { min = 18; max = 22; }
    else max = Math.min(max, 14);
    var out = new ArrayList<Integer>();
    int twinAge = min + rng.nextInt(Math.max(1, max - min + 1));
    for (int i = 0; i < count; i++) out.add(n.contains("twins") ? twinAge : min + rng.nextInt(Math.max(1, max - min + 1)));
    out.sort(Comparator.reverseOrder());
    return out;
  }

  private String nameGroup(String heritage, RandomSource rng) {
    String h = heritage == null ? "" : heritage.toLowerCase();
    for (String g : NAME_GROUPS) if (h.contains(g)) return rng.chance(0.85) ? g : "general";
    return "general";
  }

  /** First names already in the world: residents' and every off-network person's so far. */
  private Set<String> usedFirstNames;

  /** A first name nobody else in the cast has; a 30-person world with two Jims reads like a mistake. */
  private String unique(List<String> options, List<String> fallback, RandomSource rng) {
    if (usedFirstNames == null) {
      usedFirstNames = new HashSet<>();
      for (var r : roster) {
        usedFirstNames.add(r.name().split(" ")[0]);
        usedFirstNames.add(r.firstName());
      }
    }
    var free = options.stream().filter(n -> !usedFirstNames.contains(n)).toList();
    if (free.isEmpty()) free = fallback.stream().filter(n -> !usedFirstNames.contains(n)).toList();
    String pick = rng.pick(free.isEmpty() ? options : free);
    usedFirstNames.add(pick);
    return pick;
  }

  @SuppressWarnings("unchecked")
  private String adultName(String group, Gender g, RandomSource rng) {
    var row = lib.table("names").stream().filter(t -> group.equals(t.get("group"))).findFirst()
        .orElseGet(() -> lib.table("names").stream().filter(t -> "general".equals(t.get("group"))).findFirst().orElseThrow());
    return unique((List<String>) row.get(g == Gender.M ? "male" : "female"), everyName("names", g), rng);
  }

  /** Every name of that gender in a table: the fallback once a group's own list is used up. */
  @SuppressWarnings("unchecked")
  private List<String> everyName(String table, Gender g) {
    return lib.table(table).stream()
        .flatMap(t -> ((List<String>) t.get(g == Gender.M ? "male" : "female")).stream()).sorted().toList();
  }

  @SuppressWarnings("unchecked")
  private String kidName(int age, Gender g, RandomSource rng) {
    int born = settings.referenceDate().getYear() - age;
    String era = born >= 2020 ? "2020s" : born >= 2010 ? "2010s" : "2000s";
    var row = lib.table("kid_names").stream().filter(t -> era.equals(t.get("era"))).findFirst().orElseThrow();
    return unique((List<String>) row.get(g == Gender.M ? "male" : "female"), everyName("kid_names", g), rng);
  }
}
