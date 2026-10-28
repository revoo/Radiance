package app.radiance.world.genesis;

import app.radiance.content.ContentLibrary;
import app.radiance.kernel.Json;
import app.radiance.llm.Block;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.LlmRequest;
import app.radiance.llm.Part;
import app.radiance.llm.Priority;
import app.radiance.llm.Purpose;
import app.radiance.llm.output.InvalidOutputException;
import app.radiance.llm.output.OutputParser;
import app.radiance.world.genesis.WorldSeed.Resident;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Genesis steps 2–3 (SPEC §6.4): bios, backstories, voice cards, values, fears and desires, entity
 * descriptions and a canonical appearance per resident (5 at a time, grouped by circle), then 1–2
 * sentences per relationship (batched per circle). The prose comes from a {@link Source}: the
 * {@code genesis} model route, or prose authored in-session (ADR 0017). Either way it gets the same
 * checks — the output schema, handle matching and the theme linter (blacklisted themes are asked for
 * again once, then fail). Names and facts come from the skeleton and are never generated.
 * Idempotent and resumable: only empty fields are filled, and {@code checkpoint} sees every batch.
 */
public final class FleshOut {

  private static final Logger log = LoggerFactory.getLogger(FleshOut.class);

  static final int RESIDENTS_PER_CALL = 5;
  static final int PAIRS_PER_CALL = 24;

  public enum LengthBand { TERSE, SHORT, MEDIUM, LONG }

  public enum EmojiLevel { NONE, LOW, MEDIUM, HIGH }

  public enum Casing { STANDARD, LOWERCASE, SHOUTY }

  public record Batch(List<Person> residents) {}

  /** @param appearance the canonical look: apparent age, build, hair, style, typical setting (M12 avatars) */
  public record Person(
      String handle, String bio, String backstory, Voice voice, List<String> values,
      List<String> fears, List<String> desires, List<EntityLook> entities, String appearance) {}

  public record Voice(
      LengthBand lengthBand, EmojiLevel emojiLevel, Casing casing, String punctuation,
      double typoRate, List<String> slang, List<String> catchphrases, List<String> signatureTopics,
      List<String> neverDoes, List<String> samplePosts, List<String> sampleComments) {}

  public record EntityLook(String key, String visualDescription) {}

  public record Ties(List<Tie> pairs) {}

  public record Tie(String a, String b, String story) {}

  /** A flesh-out that couldn't finish; the seed keeps everything done so far. */
  public static final class GenesisException extends RuntimeException {
    public GenesisException(String message) {
      super(message);
    }
  }

  /** Where the prose comes from. Answers may be partial; FleshOut validates whatever comes back. */
  public interface Source {

    Batch residents(List<Block> prefix, String prompt, List<String> handles);

    Ties ties(List<Block> prefix, String prompt, List<String[]> pairs);

    /** Recorded in the seed: who wrote the prose. */
    String provenance();

    /** The {@code genesis} model route. */
    static Source gateway(LlmGateway gateway) {
      return new Source() {
        @Override
        public Batch residents(List<Block> prefix, String prompt, List<String> handles) {
          var result = gateway.call(new LlmRequest<>(Purpose.GENESIS, prefix, List.of(Part.text(prompt)),
              Batch.class, 1500 * handles.size(), Duration.ofMinutes(4), Priority.BACKGROUND));
          if (!result.ok()) throw new GenesisException("genesis call failed (" + result.outcome() + ") for " + handles);
          return result.value();
        }

        @Override
        public Ties ties(List<Block> prefix, String prompt, List<String[]> pairs) {
          var result = gateway.call(new LlmRequest<>(Purpose.GENESIS, prefix, List.of(Part.text(prompt)),
              Ties.class, 90 * pairs.size() + 200, Duration.ofMinutes(3), Priority.BACKGROUND));
          if (!result.ok()) throw new GenesisException("genesis ties call failed (" + result.outcome() + ")");
          return result.value();
        }

        @Override
        public String provenance() {
          return "written by the genesis model route";
        }
      };
    }

    /**
     * Prose authored in-session (ADR 0017): {@code residents-*.json} files shaped like {@link Batch} and
     * {@code ties-*.json} files shaped like {@link Ties}, keyed by file name. Each file is parsed and
     * schema-checked exactly like a model's reply.
     */
    static Source authored(Map<String, String> files, String provenance) {
      var people = new LinkedHashMap<String, Person>();
      var stories = new LinkedHashMap<String, Tie>();
      var problems = new ArrayList<String>();
      for (var f : new TreeMap<>(files).entrySet()) {
        String name = f.getKey().substring(f.getKey().lastIndexOf('/') + 1);
        try {
          if (name.startsWith("residents")) {
            for (var p : OutputParser.parse(f.getValue(), Batch.class).residents()) {
              if (people.put(p.handle(), p) != null) problems.add(name + ": @" + p.handle() + " appears twice");
            }
          } else if (name.startsWith("ties")) {
            for (var t : OutputParser.parse(f.getValue(), Ties.class).pairs()) {
              if (stories.put(pairKey(t.a(), t.b()), t) != null) {
                problems.add(name + ": the pair " + t.a() + " / " + t.b() + " appears twice");
              }
            }
          }
        } catch (InvalidOutputException e) {
          problems.add(name + ": " + e.getMessage());
        }
      }
      if (!problems.isEmpty()) {
        throw new GenesisException("authored genesis prose is invalid:\n  - " + String.join("\n  - ", problems));
      }
      return new Source() {
        @Override
        public Batch residents(List<Block> prefix, String prompt, List<String> handles) {
          return new Batch(handles.stream().filter(people::containsKey).map(people::get).toList());
        }

        @Override
        public Ties ties(List<Block> prefix, String prompt, List<String[]> pairs) {
          return new Ties(pairs.stream().map(p -> stories.get(pairKey(p[0], p[1])))
              .filter(java.util.Objects::nonNull).toList());
        }

        @Override
        public String provenance() {
          return provenance;
        }
      };
    }
  }

  private final Source source;
  private final ContentLibrary lib;
  private final String networkName;

  public FleshOut(Source source, ContentLibrary lib, String networkName) {
    this.source = source;
    this.lib = lib;
    this.networkName = networkName;
  }

  public FleshOut(LlmGateway gateway, ContentLibrary lib, String networkName) {
    this(Source.gateway(gateway), lib, networkName);
  }

  static String pairKey(String a, String b) {
    return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
  }

  // ------------------------------------------------------------------------- residents

  public WorldSeed run(WorldSeed seed, Consumer<WorldSeed> checkpoint) {
    var current = withProvenance(seed, source.provenance());
    var todo = current.residents().stream().filter(r -> r.bio() == null)
        .sorted(Comparator.comparing((Resident r) -> r.circles().isEmpty() ? "~" : r.circles().getFirst())
            .thenComparing(Resident::handle))
        .map(Resident::handle).toList();
    for (int i = 0; i < todo.size(); i += RESIDENTS_PER_CALL) {
      var handles = todo.subList(i, Math.min(todo.size(), i + RESIDENTS_PER_CALL));
      current = residents(current, handles, true);
      checkpoint.accept(current);
      log.info("Genesis: fleshed out {}/{} residents", Math.min(todo.size(), i + RESIDENTS_PER_CALL), todo.size());
    }
    current = ties(current, checkpoint);
    return current;
  }

  /** Rewrites one resident's prose; their roster identity and skeleton stay as they are. */
  public WorldSeed regenerate(WorldSeed seed, String handle) {
    if (seed.residents().stream().noneMatch(r -> r.handle().equals(handle))) {
      throw new GenesisException("no resident '" + handle + "' in the seed");
    }
    var cleared = replace(seed, seed.residents().stream()
        .map(r -> r.handle().equals(handle) ? clearProse(r) : r).toList());
    return residents(cleared, List.of(handle), true);
  }

  private WorldSeed residents(WorldSeed seed, List<String> handles, boolean retry) {
    var byHandle = seed.residents().stream().collect(Collectors.toMap(Resident::handle, r -> r));
    var batch = handles.stream().map(byHandle::get).toList();
    String prompt = lib.prompt("genesis").replace("{{network_name}}", networkName)
        .replace("{{residents}}", residentsJson(seed, batch));
    var answer = source.residents(prefix(seed), prompt, handles);
    var linter = lib.themeLinter();
    var accepted = new HashMap<String, Person>();
    for (var p : answer.residents()) {
      if (!handles.contains(p.handle()) || accepted.containsKey(p.handle())) continue;
      var hits = linter.hits(text(p));
      if (!hits.isEmpty()) {
        log.warn("Genesis: {} mentioned a blacklisted theme ({}); asking again", p.handle(), hits);
        continue;
      }
      var entityProblem = entityProblem(seed, p);
      if (entityProblem != null) {
        log.warn("Genesis: {}: {}; asking again", p.handle(), entityProblem);
        continue;
      }
      accepted.put(p.handle(), p);
    }
    var updated = replace(seed, seed.residents().stream()
        .map(r -> accepted.containsKey(r.handle()) ? merge(r, accepted.get(r.handle())) : r).toList());
    updated = withEntityLooks(updated, accepted.values());
    var missing = handles.stream().filter(h -> !accepted.containsKey(h)).toList();
    if (missing.isEmpty()) return updated;
    if (!retry) throw new GenesisException("genesis output unusable for " + missing + " after a retry");
    return residents(updated, missing, false);
  }

  /** An entity look must name one of the resident's own entities. */
  private static String entityProblem(WorldSeed seed, Person p) {
    if (p.entities() == null) return null;
    for (var e : p.entities()) {
      boolean theirs = seed.entities().stream().anyMatch(x -> x.key().equals(e.key()) && x.owner().equals(p.handle()));
      if (!theirs) return "described entity '" + e.key() + "' isn't theirs";
    }
    return null;
  }

  private static String text(Person p) {
    var sb = new StringBuilder();
    sb.append(p.bio()).append('\n').append(p.backstory()).append('\n').append(p.appearance()).append('\n');
    if (p.values() != null) p.values().forEach(v -> sb.append(v).append('\n'));
    if (p.fears() != null) p.fears().forEach(v -> sb.append(v).append('\n'));
    if (p.desires() != null) p.desires().forEach(v -> sb.append(v).append('\n'));
    if (p.voice() != null) {
      p.voice().samplePosts().forEach(v -> sb.append(v).append('\n'));
      p.voice().sampleComments().forEach(v -> sb.append(v).append('\n'));
      p.voice().catchphrases().forEach(v -> sb.append(v).append('\n'));
    }
    if (p.entities() != null) p.entities().forEach(e -> sb.append(e.visualDescription()).append('\n'));
    return sb.toString();
  }

  private static Resident merge(Resident r, Person p) {
    var v = p.voice();
    var card = new WorldSeed.VoiceCard(v.lengthBand().name(), v.emojiLevel().name(), v.casing().name(),
        v.punctuation(), Math.max(0, Math.min(0.15, v.typoRate())), cap(v.slang(), 5), cap(v.catchphrases(), 3),
        cap(v.signatureTopics(), 4), cap(v.neverDoes(), 3), cap(v.samplePosts(), 3), cap(v.sampleComments(), 2));
    return new Resident(r.handle(), r.name(), r.displayName(), r.gender(), r.birthDate(), r.heritage(),
        r.faithTradition(), r.observance(), r.livesIn(), r.occupation(), r.employer(), r.status(),
        r.partner(), r.circles(), r.pinned(), r.archetype(), r.traits(), r.quirks(), r.interests(),
        r.politics(), r.biases(), r.taste(), r.temperament(), r.routine(), r.state(), r.offNetwork(),
        p.bio(), p.backstory(), card, cap(p.values(), 3), cap(p.fears(), 3), cap(p.desires(), 3),
        p.appearance());
  }

  static Resident clearProse(Resident r) {
    return new Resident(r.handle(), r.name(), r.displayName(), r.gender(), r.birthDate(), r.heritage(),
        r.faithTradition(), r.observance(), r.livesIn(), r.occupation(), r.employer(), r.status(),
        r.partner(), r.circles(), r.pinned(), r.archetype(), r.traits(), r.quirks(), r.interests(),
        r.politics(), r.biases(), r.taste(), r.temperament(), r.routine(), r.state(), r.offNetwork(),
        null, null, null, null, null, null, null);
  }

  private static List<String> cap(List<String> l, int max) {
    if (l == null) return List.of();
    return List.copyOf(l.subList(0, Math.min(max, l.size())));
  }

  private static WorldSeed withEntityLooks(WorldSeed seed, java.util.Collection<Person> people) {
    var looks = new HashMap<String, String>();
    for (var p : people) {
      if (p.entities() == null) continue;
      for (var e : p.entities()) {
        if (e.key() != null && e.visualDescription() != null && !e.visualDescription().isBlank()) {
          looks.put(e.key(), e.visualDescription());
        }
      }
    }
    if (looks.isEmpty()) return seed;
    var entities = seed.entities().stream().map(e -> looks.containsKey(e.key())
        ? new WorldSeed.Entity(e.key(), e.owner(), e.kind(), e.name(), looks.get(e.key()), e.imageSeed(),
            e.attributes(), e.status())
        : e).toList();
    return new WorldSeed(seed.version(), seed.seed(), seed.referenceDate(), seed.residents(),
        seed.relationships(), seed.arcs(), entities, seed.provenance());
  }

  private static WorldSeed replace(WorldSeed s, List<Resident> residents) {
    return new WorldSeed(s.version(), s.seed(), s.referenceDate(), residents, s.relationships(), s.arcs(),
        s.entities(), s.provenance());
  }

  private static WorldSeed withProvenance(WorldSeed s, String provenance) {
    return new WorldSeed(s.version(), s.seed(), s.referenceDate(), s.residents(), s.relationships(), s.arcs(),
        s.entities(), provenance);
  }

  // ------------------------------------------------------------------------- ties

  private WorldSeed ties(WorldSeed seed, Consumer<WorldSeed> checkpoint) {
    var byHandle = seed.residents().stream().collect(Collectors.toMap(Resident::handle, r -> r));
    var secretPairs = new java.util.HashSet<String>();
    for (var arc : seed.arcs()) {
      if (arc.visibility() != app.radiance.kernel.Visibility.PUBLIC) {
        var people = new ArrayList<>(arc.roles().values());
        if (people.size() == 2) secretPairs.add(pairKey(people.get(0), people.get(1)));
      }
    }
    // One story per pair, grouped by the circle the two share.
    var pairs = new LinkedHashMap<String, List<String[]>>();
    var seen = new java.util.HashSet<String>();
    for (var e : seed.relationships()) {
      if (e.backstory() != null) continue;
      String a = e.from().compareTo(e.to()) < 0 ? e.from() : e.to();
      String b = a.equals(e.from()) ? e.to() : e.from();
      if (!seen.add(a + "|" + b)) continue;
      pairs.computeIfAbsent(circleOf(e, byHandle.get(a), byHandle.get(b)), k -> new ArrayList<>())
          .add(new String[] {a, b});
    }
    var current = seed;
    for (var entry : pairs.entrySet()) {
      var list = entry.getValue();
      for (int i = 0; i < list.size(); i += PAIRS_PER_CALL) {
        var chunk = list.subList(i, Math.min(list.size(), i + PAIRS_PER_CALL));
        current = tieChunk(current, entry.getKey(), chunk, secretPairs, byHandle);
        checkpoint.accept(current);
      }
    }
    return current;
  }

  /** The circle two people share (family first), or "across circles". */
  static String circleOf(WorldSeed.Relationship e, Resident a, Resident b) {
    var shared = new ArrayList<>(a.circles());
    shared.retainAll(b.circles());
    return e.kind().equals("FAMILY") ? "family" : shared.isEmpty() ? "across circles" : shared.getFirst();
  }

  private WorldSeed tieChunk(WorldSeed seed, String circle, List<String[]> chunk, java.util.Set<String> secret,
      Map<String, Resident> byHandle) {
    var arr = Json.MAPPER.createArrayNode();
    for (var pair : chunk) {
      var ab = edgeOf(seed, pair[0], pair[1]);
      var ba = edgeOf(seed, pair[1], pair[0]);
      var node = arr.addObject().put("a", pair[0]).put("b", pair[1])
          .put("aName", byHandle.get(pair[0]).name()).put("bName", byHandle.get(pair[1]).name())
          .put("kind", ab.kind()).put("aToB_affinity", ab.affinity()).put("bToA_affinity", ba.affinity())
          .put("familiarity", ab.familiarity()).put("secret", secret.contains(pairKey(pair[0], pair[1])));
      var tags = node.putArray("tags");
      ab.tags().forEach(t -> tags.add(pair[0] + "->" + pair[1] + ": " + t));
      ba.tags().forEach(t -> tags.add(pair[1] + "->" + pair[0] + ": " + t));
    }
    String prompt = lib.prompt("genesis-ties").replace("{{circle}}", circle).replace("{{pairs}}", arr.toPrettyString());
    var answer = source.ties(prefix(seed), prompt, chunk);
    var linter = lib.themeLinter();
    var stories = new HashMap<String, String>();
    for (var t : answer.pairs()) {
      if (t.story() == null || t.story().isBlank() || !linter.hits(t.story()).isEmpty()) continue;
      stories.put(pairKey(t.a(), t.b()), t.story().strip());
    }
    var rels = seed.relationships().stream().map(e -> {
      String story = stories.get(pairKey(e.from(), e.to()));
      return story == null || e.backstory() != null ? e
          : new WorldSeed.Relationship(e.from(), e.to(), e.kind(), e.affinity(), e.trust(), e.attraction(),
              e.familiarity(), e.respect(), e.chemistry(), e.tags(), e.connected(), story);
    }).toList();
    return new WorldSeed(seed.version(), seed.seed(), seed.referenceDate(), seed.residents(), rels,
        seed.arcs(), seed.entities(), seed.provenance());
  }

  private static WorldSeed.Relationship edgeOf(WorldSeed s, String from, String to) {
    return s.relationships().stream().filter(e -> e.from().equals(from) && e.to().equals(to)).findFirst()
        .orElseThrow();
  }

  // ------------------------------------------------------------------------- prompt pieces

  /** World bible + cast directory: stable across every genesis call, so it caches. */
  List<Block> prefix(WorldSeed seed) {
    String themes = lib.blacklist().stream().flatMap(b -> b.themeTerms().stream()).collect(Collectors.joining(", "));
    String bible = lib.prompt("world-bible").replace("{{network_name}}", networkName)
        .replace("{{blacklisted_themes}}", themes.isEmpty() ? "(none)" : themes);
    var ref = LocalDate.parse(seed.referenceDate());
    var dir = new StringBuilder("# Cast directory\n");
    for (var r : seed.residents()) {
      dir.append("- @").append(r.handle()).append(" — ").append(r.name())
          .append(r.displayName() != null ? " (goes by " + r.displayName() + ")" : "")
          .append(", ").append(Period.between(LocalDate.parse(r.birthDate()), ref).getYears())
          .append(", ").append(r.occupation()).append(r.employer() != null ? " at " + r.employer() : "")
          .append(", ").append(r.livesIn()).append("; ").append(String.join(", ", r.circles())).append('\n');
    }
    return List.of(new Block(bible), new Block(dir.toString()));
  }

  private String residentsJson(WorldSeed seed, List<Resident> batch) {
    var ref = LocalDate.parse(seed.referenceDate());
    var names = seed.residents().stream().collect(Collectors.toMap(Resident::handle, Resident::name));
    var arr = Json.MAPPER.createArrayNode();
    for (var r : batch) {
      var n = arr.addObject();
      n.put("handle", r.handle()).put("name", r.name()).put("goesBy", r.displayName())
          .put("gender", r.gender().name())
          .put("age", Period.between(LocalDate.parse(r.birthDate()), ref).getYears())
          .put("heritage", r.heritage()).put("faith", r.faithTradition()).put("observance", r.observance().name())
          .put("livesIn", r.livesIn()).put("job", r.occupation()).put("employer", r.employer())
          .put("status", r.status().name()).put("partner", r.partner() == null ? null : names.get(r.partner()))
          .put("archetype", r.archetype());
      n.set("circles", Json.MAPPER.valueToTree(r.circles()));
      n.set("traits", Json.MAPPER.valueToTree(r.traits()));
      var quirks = n.putArray("quirks");
      for (String q : r.quirks()) {
        var def = lib.quirks().get(q.contains(":") ? q.substring(0, q.indexOf(':')) : q);
        quirks.addObject().put("quirk", q).put("description", def.description()).put("voiceHint", def.voiceHint());
      }
      n.set("interests", Json.MAPPER.valueToTree(r.interests()));
      n.set("politics", Json.MAPPER.valueToTree(r.politics()));
      n.set("biases", Json.MAPPER.valueToTree(r.biases()));
      n.put("temperament", r.temperament().preset().name()).put("schedule", r.routine().schedule());
      n.set("offNetworkFamily", Json.MAPPER.valueToTree(r.offNetwork()));
      var ents = n.putArray("entities");
      seed.entities().stream().filter(e -> e.owner().equals(r.handle()))
          .forEach(e -> ents.addObject().put("key", e.key()).put("kind", e.kind().name()).put("name", e.name())
              .put("currentDescription", e.visualDescription()));
      var ties = n.putArray("closestTies");
      seed.relationships().stream().filter(e -> e.from().equals(r.handle()))
          .sorted(Comparator.comparingDouble(WorldSeed.Relationship::familiarity).reversed()).limit(6)
          .forEach(e -> ties.addObject().put("with", names.get(e.to())).put("kind", e.kind())
              .put("affinity", e.affinity()).set("tags", Json.MAPPER.valueToTree(e.tags().stream()
                  .filter(t -> !t.equals("suspects")).toList())));
      var arcs = n.putArray("storylines");
      seed.arcs().stream().filter(a -> a.roles().containsValue(r.handle()))
          .forEach(a -> arcs.addObject().put("arc", a.defId()).put("phase", a.phase())
              .put("visibility", a.visibility().name())
              .put("with", a.roles().values().stream().filter(h -> !h.equals(r.handle())).map(names::get)
                  .collect(Collectors.joining(", "))));
    }
    return arr.toPrettyString();
  }
}
