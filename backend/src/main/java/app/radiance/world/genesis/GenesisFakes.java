package app.radiance.world.genesis;

import app.radiance.kernel.Json;
import app.radiance.kernel.RandomSource;
import app.radiance.llm.FakeResponses;
import app.radiance.world.genesis.FleshOut.Casing;
import app.radiance.world.genesis.FleshOut.EmojiLevel;
import app.radiance.world.genesis.FleshOut.LengthBand;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Offline genesis prose (FakeLlm templates): plain, deterministic and consistent with the skeleton, so
 * the {@code offline} profile builds a usable world with zero keys. Real prose needs a real model.
 */
public final class GenesisFakes {

  private static final Pattern RESIDENTS = Pattern.compile("<residents>(.*)</residents>", Pattern.DOTALL);
  private static final Pattern PAIRS = Pattern.compile("<pairs>(.*)</pairs>", Pattern.DOTALL);

  private GenesisFakes() {}

  public static void register(FakeResponses fakes) {
    fakes.register(FleshOut.Batch.class, call -> batch(call.allContent()));
    fakes.register(FleshOut.Ties.class, call -> ties(call.allContent()));
  }

  static FleshOut.Batch batch(String content) {
    var m = RESIDENTS.matcher(content);
    if (!m.find()) return new FleshOut.Batch(List.of());
    var people = new ArrayList<FleshOut.Person>();
    for (JsonNode r : Json.tree(m.group(1).strip())) people.add(person(r));
    return new FleshOut.Batch(people);
  }

  private static FleshOut.Person person(JsonNode r) {
    String handle = r.path("handle").asString();
    var rng = RandomSource.seeded(handle.hashCode()).derive("fake-genesis");
    String first = r.path("goesBy").isString() && !r.path("goesBy").asString().isBlank()
        ? r.path("goesBy").asString().split(" ")[0] : r.path("name").asString().split(" ")[0];
    String job = r.path("job").asString();
    String place = r.path("livesIn").asString();
    int extraversion = r.path("traits").path("extraversion").asInt(50);
    int humor = r.path("traits").path("humor").asInt(50);
    String bio = rng.pick(List.of(
        job + ". " + place.split(",")[0] + ".",
        place.split(",")[0] + " | " + job.toLowerCase(),
        "Coffee, then " + job.toLowerCase() + ". " + place.split(",")[0] + " forever."));
    String backstory = first + " is " + r.path("age").asInt() + " and lives in " + place + ", working as "
        + article(job) + job.toLowerCase()
        + (r.path("employer").isString() ? " at " + r.path("employer").asString() : "") + ". "
        + "Friends would call " + pronoun(r) + " " + (extraversion > 60 ? "the loud one in the group chat" : "quietly reliable")
        + (humor > 60 ? " with a quick sense of humor" : "") + ". "
        + (r.path("partner").isString() ? first + " is with " + r.path("partner").asString() + ". " : "")
        + "(Placeholder backstory written offline; a real model writes this at genesis.)";
    var voice = new FleshOut.Voice(
        extraversion > 65 ? LengthBand.MEDIUM : LengthBand.SHORT,
        extraversion > 70 ? EmojiLevel.MEDIUM : extraversion > 40 ? EmojiLevel.LOW : EmojiLevel.NONE,
        rng.chance(0.25) ? Casing.LOWERCASE : Casing.STANDARD,
        rng.pick(List.of("ellipses...", "no periods", "lots of exclamation points", "plain")),
        Math.round(rng.nextDouble(0, 0.06) * 100) / 100.0,
        List.of(rng.pick(List.of("lol", "ngl", "bro", "fr", "ope"))), List.of(),
        List.of(job.toLowerCase().split(" ")[0]), List.of("posts selfies"),
        List.of("long day but we made it", "anyone know a good mechanic out by " + place.split(",")[0] + "?",
            "best part of the week right here"),
        List.of("lol same", "this is so true"));
    var entities = new ArrayList<FleshOut.EntityLook>();
    for (JsonNode e : r.path("entities")) {
      entities.add(new FleshOut.EntityLook(e.path("key").asString(), e.path("currentDescription").asString()));
    }
    return new FleshOut.Person(handle, bio, backstory, voice, List.of("family", "showing up for friends"),
        List.of("ending up stuck"), List.of("a long weekend somewhere warm"), entities);
  }

  private static String article(String job) {
    String j = job.toLowerCase();
    if (j.startsWith("retired") || j.contains(";")) return "";
    return "aeiou".indexOf(j.charAt(0)) >= 0 ? "an " : "a ";
  }

  private static String pronoun(JsonNode r) {
    return "F".equals(r.path("gender").asString()) ? "her" : "him";
  }

  static FleshOut.Ties ties(String content) {
    var m = PAIRS.matcher(content);
    if (!m.find()) return new FleshOut.Ties(List.of());
    var out = new ArrayList<FleshOut.Tie>();
    for (JsonNode p : Json.tree(m.group(1).strip())) {
      String kind = p.path("kind").asString().toLowerCase().replace('_', ' ');
      out.add(new FleshOut.Tie(p.path("a").asString(), p.path("b").asString(),
          p.path("aName").asString() + " and " + p.path("bName").asString() + " know each other as "
              + (kind.equals("ex") ? "exes" : kind + "s") + "."));
    }
    return new FleshOut.Ties(out);
  }
}
