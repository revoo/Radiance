package app.radiance.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The feel bands CI asserts at default knobs (SPEC §10), on 30 simulated days of seed 42. Bands that
 * need the Pacer are M7's.
 */
class FeelBandsTests {

  static final class NightlyBatchLib {
    static final app.radiance.content.ContentLibrary LIB = app.radiance.content.ClasspathContent.load(java.util.List.of());
  }

  @TempDir Path dir;

  @Test
  void aMonthWithAWarmRStaysInEveryBand() {
    try (var sim = new Simulation(Simulation.Options.of(42, 30, Persona.WARM, dir.resolve("warm"))).run()) {
      var report = FeelReport.of(sim);
      assertThat(report.bands).as(report.render(sim)).allMatch(FeelReport.Band::ok);
      assertThat(sim.spoilers).isEmpty();
      // Regression: the thresholds tuned for R left friends' posts with no comments at all, and the feed felt dead.
      assertThat(report.residentPostComments).isGreaterThanOrEqualTo(1);
    }
  }

  /** Regression: Donna posted about "her" kitchen when the renovation was Sam's, and every post of R's got one comment from Donna. */
  @Test
  void aFriendTellsSomeoneElsesStoryAsTheirs_AndRsPostsHearFromDifferentPeople() {
    try (var sim = new Simulation(Simulation.Options.of(42, 30, Persona.WARM, dir.resolve("told"))).run()) {
      var told = sim.sql.query("SELECT p.author_id AS a, p.brief_json AS b, s.actor_id AS subject FROM post p JOIN arc_participant s ON s.arc_id = p.arc_id"
          + " WHERE p.arc_id IS NOT NULL AND p.brief_json LIKE '%\"beat\":\"MENTION\"%'", java.util.List.of(),
          r -> new String[] {r.str("a"), r.str("b"), r.str("subject")});
      told.stream().filter(x -> !x[0].equals(x[2])).forEach(x -> assertThat(x[1]).contains("which is theirs and not the writer's"));
      var r = sim.sql.queryOne("SELECT id FROM actor WHERE kind = 'USER'", java.util.List.of(), x -> x.str("id")).orElseThrow();
      var posts = sim.sql.query("SELECT id FROM post WHERE author_id = ?", java.util.List.of(r), x -> x.str("id"));
      assertThat(posts).isNotEmpty();
      var first = new java.util.HashSet<String>();
      for (String id : posts) {
        var authors = sim.sql.query("SELECT author_id FROM comment WHERE post_id = ? AND parent_id IS NULL ORDER BY created_at", java.util.List.of(id), x -> x.str("author_id"));
        assertThat(authors.size()).as("comments on a post of R's").isGreaterThanOrEqualTo(2);
        first.add(authors.getFirst());
      }
      assertThat(first.size()).isGreaterThan(3);
    }
  }

  /** Regression: seven residents bought a couch in a fortnight. */
  @Test
  void theSameLifeEventDoesntSweepTheCast() {
    try (var sim = new Simulation(Simulation.Options.of(42, 30, Persona.WARM, dir.resolve("sweep"))).run()) {
      var seen = new java.util.HashMap<String, java.util.List<Long>>();
      sim.sql.query("SELECT brief_json, created_at FROM post WHERE arc_id IS NULL AND brief_json IS NOT NULL", java.util.List.of(), r -> new Object[] {r.str("brief_json"), r.lng("created_at")})
          .forEach(row -> {
            var brief = app.radiance.sim.Brief.parse((String) row[0]);
            var def = brief.defId() == null ? null : NightlyBatchLib.LIB.events().get(brief.defId());
            if (def != null && def.when() == null && java.util.Set.of("LIFE", "HOBBY", "FAMILY").contains(def.category().name())) {
              seen.computeIfAbsent(def.id(), k -> new java.util.ArrayList<>()).add((Long) row[1]);
            }
          });
      assertThat(seen).isNotEmpty();
      seen.forEach((id, times) -> {
        java.util.Collections.sort(times);
        for (int i = 1; i < times.size(); i++) {
          // the posts land a little either side of their events
          double days = Planner.spacingDays(NightlyBatchLib.LIB.events().get(id)) - 1;
          if (days < 0) return;
          assertThat(times.get(i) - times.get(i - 1)).as(id).isGreaterThan((long) (days * 86_400_000L));
        }
      });
    }
  }

  @Test
  void aPretentiousRCollectsFinds_pretentiousAndFewerCommentsThanAWarmOne() {
    try (var warm = new Simulation(Simulation.Options.of(42, 30, Persona.WARM, dir.resolve("w"))).run();
        var pretentious = new Simulation(Simulation.Options.of(42, 30, Persona.PRETENTIOUS, dir.resolve("p"))).run()) {
      var w = FeelReport.of(warm);
      var p = FeelReport.of(pretentious);
      assertThat(p.userTags.getOrDefault("finds_pretentious", 0)).isGreaterThan(w.userTags.getOrDefault("finds_pretentious", 0));
      assertThat(p.userPostComments).isLessThan(w.userPostComments);
    }
  }
}
