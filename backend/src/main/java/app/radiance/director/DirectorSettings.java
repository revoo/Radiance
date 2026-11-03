package app.radiance.director;

import app.radiance.batch.ImageWork;
import app.radiance.batch.NightlyBatch;
import app.radiance.content.ContentLibrary;
import app.radiance.content.Rules.PacingRules;
import app.radiance.kernel.Json;
import app.radiance.live.LivePlay;
import app.radiance.llm.LlmConsole;
import app.radiance.llm.LlmProperties;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.Knobs;
import app.radiance.sim.WorldEngine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The live-tuned settings (SPEC §5.16): knobs, the arc blacklist, LLM routes, budgets, images per day and pacing rules, kept in
 * {@code world.settings_json} and put back at startup in every run mode. A section of a change either all takes or none of the
 * change does: everything is checked before anything is applied.
 */
@Component
public class DirectorSettings implements SmartInitializingSingleton {

  /** Thrown for a setting that can't work; the message is for R. */
  static final class Invalid extends RuntimeException {
    Invalid(String message) {
      super(message);
    }
  }

  /** A knob the console can move, and how far. */
  public record Range(double min, double max, boolean whole) {}

  private static final Logger log = LoggerFactory.getLogger(DirectorSettings.class);

  static final Map<String, Range> NUMBERS = numbers();
  static final Set<String> MIXES = Set.of("eventMix", "contentKindWeights", "regionMix");
  static final Set<String> PROFILES = Set.of("STEADY", "CHAOTIC", "COZY");
  private static final double MAX_USD = 1_000;
  private static final int MAX_IMAGES = 500;

  private static Map<String, Range> numbers() {
    var m = new LinkedHashMap<String, Range>();
    m.put("dramaIntensity", new Range(0, 3, false));
    m.put("maxMajorEventsPer14d", new Range(0, 10, true));
    m.put("maxDramaPerDay", new Range(0, 10, true));
    m.put("userSalience", new Range(0, 5, false));
    m.put("maxUserShareOfAttention", new Range(0, 1, false));
    m.put("impressionSensitivity", new Range(0, 3, false));
    m.put("crushPropensity", new Range(0, 3, false));
    m.put("dmInitiationRate", new Range(0, 3, false));
    m.put("edge", new Range(0, 2, false));
    m.put("temptation", new Range(0, 3, false));
    m.put("headlinesPerDay", new Range(0, 100, true));
    m.put("primeTimeBoost", new Range(1, 3, false));
    m.put("moodHalfLifeHours", new Range(1, 240, false));
    m.put("affinityHalfLifeDays", new Range(1, 120, false));
    m.put("maxTraitDrift", new Range(0, 30, false));
    m.put("scriptSlackHours", new Range(0, 24, false));
    return java.util.Collections.unmodifiableMap(m);
  }

  private final Sql sql;
  private final WriterLane lane;
  private final WorldEngine engine;
  private final ContentLibrary lib;
  private final LlmConsole llm;
  private final NightlyBatch batch;
  private final ObjectProvider<LivePlay> live;
  private final Knobs baseKnobs;
  private final Set<String> lockedBlacklist = new TreeSet<>();
  private final LlmProperties.Budgets baseBudgets;
  private final ImageWork.Limits baseImages;
  private final PacingRules basePacing;
  private ObjectNode stored = Json.MAPPER.createObjectNode();

  DirectorSettings(Sql sql, WriterLane lane, WorldEngine engine, LlmConsole llm, NightlyBatch batch, ObjectProvider<LivePlay> live) {
    this.sql = sql;
    this.lane = lane;
    this.engine = engine;
    this.lib = engine.lib();
    this.llm = llm;
    this.batch = batch;
    this.live = live;
    this.baseKnobs = engine.knobs();
    lib.blacklist().forEach(b -> lockedBlacklist.add(b.id()));
    this.baseBudgets = llm.budgets();
    this.baseImages = batch.imageLimits();
    this.basePacing = lib.pacing();
  }

  /** Before anything runs: the batch and the first session get the tuned world. */
  @Override
  public synchronized void afterSingletonsInstantiated() {
    String json;
    try {
      json = sql.queryOne("SELECT settings_json FROM world", List.of(), r -> r.str("settings_json")).orElse("{}");
    } catch (RuntimeException e) {
      log.warn("No settings loaded: {}", e.toString());
      return;
    }
    if (!(Json.tree(json) instanceof ObjectNode saved)) return;
    for (var section : saved.properties()) {
      try {
        var patch = Json.MAPPER.createObjectNode();
        patch.set(section.getKey(), section.getValue());
        prepare(patch).forEach(Runnable::run);
        stored.set(section.getKey(), section.getValue());
      } catch (RuntimeException e) {
        // a setting that no longer fits the config mustn't keep the world from starting
        log.warn("Ignoring the saved '{}' setting: {}", section.getKey(), e.getMessage());
      }
    }
  }

  /** Applies {@code patch}: a section that's present replaces what's there, and null puts it back to the config's. */
  public synchronized void update(JsonNode patch) {
    if (patch == null || !patch.isObject()) throw new Invalid("Settings must be an object");
    var steps = prepare(patch);
    var next = stored.deepCopy();
    for (var section : patch.properties()) {
      if (section.getValue().isNull()) next.remove(section.getKey());
      else next.set(section.getKey(), section.getValue());
    }
    String json = Json.write(next);
    lane.run(() -> sql.commit(new UnitOfWork().add("UPDATE world SET settings_json = ?", json)));
    steps.forEach(Runnable::run);
    stored = next;
  }

  private List<Runnable> prepare(JsonNode patch) {
    var steps = new ArrayList<Runnable>();
    for (var section : patch.properties()) {
      JsonNode value = section.getValue();
      switch (section.getKey()) {
        case "knobs" -> {
          var knobs = value.isNull() ? baseKnobs : knobs(value);
          steps.add(() -> engine.tune(knobs));
        }
        case "blacklist" -> {
          var blocked = value.isNull() ? lockedBlacklist : blacklist(value);
          steps.add(() -> lib.arcs().keySet().forEach(id -> {
            if (blocked.contains(id)) lib.block(id);
            else lib.unblock(id);
          }));
        }
        case "routes" -> {
          var merged = routes(value);
          steps.add(() -> llm.reroute(merged));
        }
        case "budgets" -> {
          var budgets = value.isNull() ? baseBudgets : budgets(value);
          steps.add(() -> llm.setBudgets(budgets));
        }
        case "imagesPerDay" -> {
          int images = value.isNull() ? baseImages.photosPerDay() : images(value);
          steps.add(() -> batch.tuneImages(new ImageWork.Limits(images, baseImages.neuronBudget(), baseImages.neuronsPerImage())));
        }
        case "pacing" -> {
          var rules = value.isNull() ? basePacing : pacing(value);
          steps.add(() -> live.ifAvailable(l -> l.tunePacing(rules)));
        }
        default -> throw new Invalid("No setting called " + section.getKey());
      }
    }
    return steps;
  }

  private Knobs knobs(JsonNode overrides) {
    if (!overrides.isObject()) throw new Invalid("Knobs must be an object");
    var tree = (ObjectNode) Json.MAPPER.valueToTree(baseKnobs);
    for (var e : overrides.properties()) {
      String name = e.getKey();
      JsonNode v = e.getValue();
      var range = NUMBERS.get(name);
      if (range != null) {
        if (!v.isNumber() || (range.whole() && !v.isIntegralNumber())) throw new Invalid(name + " must be a " + (range.whole() ? "whole " : "") + "number");
        if (v.asDouble() < range.min() || v.asDouble() > range.max()) throw new Invalid(name + " must be between " + range.min() + " and " + range.max());
      } else if (MIXES.contains(name)) {
        mix(name, v, tree.get(name));
      } else if (name.equals("profile")) {
        if (!PROFILES.contains(v.asString(""))) throw new Invalid("profile must be one of " + new TreeSet<>(PROFILES));
      } else if (name.equals("affairs")) {
        if (!v.isBoolean()) throw new Invalid("affairs must be true or false");
      } else {
        throw new Invalid("No knob called " + name);
      }
      tree.set(name, v);
    }
    return Json.MAPPER.treeToValue(tree, Knobs.class);
  }

  /** A mix keeps its categories, and some of it has to be left. */
  private static void mix(String name, JsonNode v, JsonNode base) {
    if (!v.isObject()) throw new Invalid(name + " must be an object");
    var keys = new HashSet<String>();
    double total = 0;
    for (var e : v.properties()) {
      keys.add(e.getKey());
      if (!e.getValue().isNumber() || e.getValue().asDouble() < 0) throw new Invalid(name + ": " + e.getKey() + " can't be negative");
      total += e.getValue().asDouble();
    }
    var expected = new HashSet<String>();
    base.properties().forEach(e -> expected.add(e.getKey()));
    if (!keys.equals(expected)) throw new Invalid(name + " must have exactly " + new TreeSet<>(expected));
    if (total <= 0) throw new Invalid(name + " can't be all zero");
  }

  private Set<String> blacklist(JsonNode ids) {
    if (!ids.isArray()) throw new Invalid("The blacklist must be a list of arcs");
    var out = new TreeSet<>(lockedBlacklist);
    for (var id : ids) {
      if (!lib.arcs().containsKey(id.asString(""))) throw new Invalid("No arc called " + id.asString(""));
      out.add(id.asString());
    }
    return out;
  }

  private Map<String, LlmProperties.Route> routes(JsonNode node) {
    var overrides = new LinkedHashMap<String, LlmProperties.Route>();
    if (!node.isNull()) {
      if (!node.isObject()) throw new Invalid("Routes must be an object");
      for (var e : node.properties()) {
        try {
          overrides.put(e.getKey(), Json.MAPPER.treeToValue(e.getValue(), LlmProperties.Route.class));
        } catch (RuntimeException x) {
          throw new Invalid("Route " + e.getKey() + " needs a model and a list of fallbacks");
        }
        if (overrides.get(e.getKey()).model() == null) throw new Invalid("Route " + e.getKey() + " needs a model");
      }
    }
    try {
      return llm.checked(overrides);
    } catch (IllegalStateException x) {
      throw new Invalid(x.getMessage());
    }
  }

  private LlmProperties.Budgets budgets(JsonNode node) {
    double daily = usd(node, "dailyUsd", llm.budgets().dailyUsd());
    double batchUsd = usd(node, "perBatchUsd", llm.budgets().perBatchUsd());
    double monthly = usd(node, "monthlyUsd", llm.budgets().monthlyUsd());
    return new LlmProperties.Budgets(daily, batchUsd, monthly);
  }

  private static double usd(JsonNode node, String name, double fallback) {
    var v = node.get(name);
    if (v == null) return fallback;
    if (!v.isNumber() || v.asDouble() < 0 || v.asDouble() > MAX_USD) throw new Invalid(name + " must be between 0 and " + (int) MAX_USD);
    return v.asDouble();
  }

  private static int images(JsonNode v) {
    if (!v.isIntegralNumber() || v.asInt() < 0 || v.asInt() > MAX_IMAGES) throw new Invalid("imagesPerDay must be a whole number from 0 to " + MAX_IMAGES);
    return v.asInt();
  }

  private PacingRules pacing(JsonNode node) {
    try {
      var rules = Json.MAPPER.treeToValue(node, PacingRules.class);
      LivePlay.checkPacing(rules);
      return rules;
    } catch (RuntimeException x) {
      throw new Invalid("Those pacing rules can't work: " + x.getMessage());
    }
  }

  /** What the console shows. */
  public record Current(Knobs knobs, Knobs knobDefaults, Map<String, Range> ranges, List<String> profiles, List<Blocked> blacklist,
      List<String> arcs, Map<String, LlmProperties.Route> routes, List<LlmConsole.ModelView> models, LlmProperties.Budgets budgets,
      int imagesPerDay, PacingRules pacing) {}

  /** A blacklisted arc; a locked one comes from the config and stays. */
  public record Blocked(String id, boolean locked) {}

  public synchronized Current current() {
    var blocked = lib.blacklist().stream().map(b -> new Blocked(b.id(), lockedBlacklist.contains(b.id())))
        .sorted(java.util.Comparator.comparing(Blocked::id)).toList();
    var pacing = stored.has("pacing") ? Json.MAPPER.treeToValue(stored.get("pacing"), PacingRules.class) : basePacing;
    return new Current(engine.knobs(), baseKnobs, NUMBERS, PROFILES.stream().sorted().toList(), blocked,
        lib.arcs().keySet().stream().sorted().toList(), llm.routes(), llm.models(), llm.budgets(), batch.imageLimits().photosPerDay(), pacing);
  }
}
