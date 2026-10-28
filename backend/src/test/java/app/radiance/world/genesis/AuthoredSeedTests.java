package app.radiance.world.genesis;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.kernel.Json;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The prose authored in-session for seed 7 (ADR 0017) gets the checks a model's reply would: it
 * parses against the output schema, names real residents of the seed-7 skeleton, describes only their
 * own entities, and never touches a blacklisted theme.
 */
class AuthoredSeedTests {

  static final ContentLibrary LIB = ClasspathContent.load(List.of(new ContentProperties.BlacklistEntry(
      "pregnancy", List.of("pregnant", "pregnancy", "expecting a baby", "baby bump", "trimester",
          "baby shower", "gender reveal"))));

  static WorldSeed skeleton() {
    return SkeletonGenerator.generate(LIB, new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
  }

  static Map<String, String> files() {
    return ClasspathContent.files("content/genesis/seed-7/", "json");
  }

  static List<String> authoredHandles() {
    var out = new ArrayList<String>();
    files().forEach((name, text) -> {
      if (name.startsWith("residents")) Json.tree(text).path("residents").forEach(p -> out.add(p.path("handle").asString()));
    });
    return out;
  }

  @Test
  void everyAuthoredResidentPassesTheSameChecksAsAModelReply() {
    var seed = skeleton();
    var source = FleshOut.Source.authored(files(), "authored in-session by Opus"); // schema + duplicates
    var fleshOut = new FleshOut(source, LIB, "Radiance");
    var handles = authoredHandles();
    assertThat(handles).isNotEmpty();
    var known = seed.residents().stream().map(WorldSeed.Resident::handle).toList();
    for (String h : handles) {
      assertThat(known).as("authored @" + h + " is a resident").contains(h);
      var r = fleshOut.regenerate(seed, h).residents().stream().filter(x -> x.handle().equals(h)).findFirst().orElseThrow();
      assertThat(r.backstory().split("\\s+").length).as("@" + h + " backstory words").isBetween(110, 190);
      assertThat(r.bio().split("\\s+").length).as("@" + h + " bio words").isLessThanOrEqualTo(25);
      assertThat(r.appearance()).as("@" + h + " appearance").isNotBlank();
      assertThat(r.voice().samplePosts()).hasSize(3);
      assertThat(r.voice().sampleComments()).hasSize(2);
    }
  }
}
