package app.radiance.world.genesis;

import app.radiance.content.ContentLibrary;
import app.radiance.kernel.Json;
import app.radiance.llm.Block;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.LlmRequest;
import app.radiance.llm.Part;
import app.radiance.llm.Priority;
import app.radiance.llm.Purpose;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Genesis steps 2–3 (SPEC §6.4): the {@code genesis} route writes bios, backstories, voice cards,
 * values, fears and desires (5 residents per call, grouped by circle), entity descriptions, then 1–2
 * sentences per relationship (batched per circle). Names and facts come from the skeleton and are
 * never generated. Output mentioning a blacklisted theme is regenerated once, then fails.
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

  public record Person(
      String handle, String bio, String backstory, Voice voice, List<String> values,
      List<String> fears, List<String> desires, List<EntityLook> entities) {}

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

  private final LlmGateway gateway;
  private final ContentLibrary lib;
  private final String networkName;

  public FleshOut(LlmGateway gateway, ContentLibrary lib, String networkName) {
    this.gateway = gateway;
    this.lib = lib;
    this.networkName = networkName;
  }

  // ------------------------------------------------------------------------- residents

  public WorldSeed run(WorldSeed seed, Consumer<WorldSeed> checkpoint) {
    var current = seed;
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
    var result = gateway.call(new LlmRequest<>(Purpose.GENESIS, prefix(seed),
        List.of(Part.text(lib.prompt("genesis").replace("{{network_name}}", networkName)
            .replace("{{residents}}", residentsJson(seed, batch)))),
        Batch.class, 1400 * batch.size(), Duration.ofMinutes(4), Priority.BACKGROUND));
    if (!result.ok()) {
      throw new GenesisException("genesis call failed (" + result.outcome() + ") for " + handles);
    }
    var linter = lib.themeLinter();
    var accepted = new HashMap<String, Person>();
    for (var p : result.value().residents()) {
      if (!handles.contains(p.handle()) || accepted.containsKey(p.handle())) continue;
      var hits = linter.hits(text(p));
      if (!hits.isEmpty()) {
        log.warn("Genesis: {} mentioned a blacklisted theme ({}); regenerating", p.handle(), hits);
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

  private static String text(Person p) {
    var sb = new StringBuilder();
    sb.append(p.bio()).append('\n').append(p.backstory()).append('\n');
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
        p.bio(), p.backstory(), card, cap(p.values(), 3), cap(p.fears(), 3), cap(p.desires(), 3));
  }

  static Resident clearProse(Resident r) {
    return new Resident(r.handle(), r.name(), r.displayName(), r.gender(), r.birthDate(), r.heritage(),
        r.faithTradition(), r.observance(), r.livesIn(), r.occupation(), r.employer(), r.status(),
        r.partner(), r.circles(), r.pinned(), r.archetype(), r.traits(), r.quirks(), r.interests(),
        r.politics(), r.biases(), r.taste(), r.temperament(), r.routine(), r.state(), r.offNetwork(),
        null, null, null, null, null, null);
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
        seed.relationships(), seed.arcs(), entities);
  }

  private static WorldSeed replace(WorldSeed s, List<Resident> residents) {
    return new WorldSeed(s.version(), s.seed(), s.referenceDate(), residents, s.relationships(), s.arcs(),
        s.entities());
  }

  // ------------------------------------------------------------------------- ties

  private WorldSeed ties(WorldSeed seed, Consumer<WorldSeed> checkpoint) {
    var byHandle = seed.residents().stream().collect(Collectors.toMap(Resident::handle, r -> r));
    var secretPairs = new java.util.HashSet<String>();
    for (var arc : seed.arcs()) {
      if (arc.visibility() != app.radiance.kernel.Visibility.PUBLIC) {
        var people = new ArrayList<>(arc.roles().values());
        java.util.Collections.sort(people);
        if (people.size() == 2) secretPairs.add(people.get(0) + "|" + people.get(1));
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
      var shared = new ArrayList<>(byHandle.get(a).circles());
      shared.retainAll(byHandle.get(b).circles());
      String circle = e.kind().equals("FAMILY") ? "family" : shared.isEmpty() ? "across circles" : shared.getFirst();
      pairs.computeIfAbsent(circle, k -> new ArrayList<>()).add(new String[] {a, b});
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

  private WorldSeed tieChunk(WorldSeed seed, String circle, List<String[]> chunk, java.util.Set<String> secret,
      Map<String, Resident> byHandle) {
    var arr = Json.MAPPER.createArrayNode();
    for (var pair : chunk) {
      var ab = edgeOf(seed, pair[0], pair[1]);
      var ba = edgeOf(seed, pair[1], pair[0]);
      var node = arr.addObject().put("a", pair[0]).put("b", pair[1])
          .put("aName", byHandle.get(pair[0]).name()).put("bName", byHandle.get(pair[1]).name())
          .put("kind", ab.kind()).put("aToB_affinity", ab.affinity()).put("bToA_affinity", ba.affinity())
          .put("familiarity", ab.familiarity()).put("secret", secret.contains(pair[0] + "|" + pair[1]));
      var tags = node.putArray("tags");
      ab.tags().forEach(t -> tags.add(pair[0] + "->" + pair[1] + ": " + t));
      ba.tags().forEach(t -> tags.add(pair[1] + "->" + pair[0] + ": " + t));
    }
    var result = gateway.call(new LlmRequest<>(Purpose.GENESIS, prefix(seed),
        List.of(Part.text(lib.prompt("genesis-ties").replace("{{circle}}", circle)
            .replace("{{pairs}}", arr.toPrettyString()))),
        Ties.class, 90 * chunk.size() + 200, Duration.ofMinutes(3), Priority.BACKGROUND));
    if (!result.ok()) throw new GenesisException("genesis ties call failed (" + result.outcome() + ")");
    var linter = lib.themeLinter();
    var stories = new HashMap<String, String>();
    for (var t : result.value().pairs()) {
      if (t.story() == null || t.story().isBlank() || !linter.hits(t.story()).isEmpty()) continue;
      String a = t.a().compareTo(t.b()) < 0 ? t.a() : t.b();
      String b = a.equals(t.a()) ? t.b() : t.a();
      stories.put(a + "|" + b, t.story().strip());
    }
    var rels = seed.relationships().stream().map(e -> {
      String a = e.from().compareTo(e.to()) < 0 ? e.from() : e.to();
      String b = a.equals(e.from()) ? e.to() : e.from();
      String story = stories.get(a + "|" + b);
      return story == null || e.backstory() != null ? e
          : new WorldSeed.Relationship(e.from(), e.to(), e.kind(), e.affinity(), e.trust(), e.attraction(),
              e.familiarity(), e.respect(), e.chemistry(), e.tags(), e.connected(), story);
    }).toList();
    return new WorldSeed(seed.version(), seed.seed(), seed.referenceDate(), seed.residents(), rels,
        seed.arcs(), seed.entities());
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
