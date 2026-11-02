package app.radiance.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SPEC §5.9, §10: a tag flips only after crossing its threshold by a margin and staying there for 2 world-days. */
class ImpressionTagTests {

  static final long DAY = 86_400_000L;
  static final long T = 1_000 * DAY;

  @TempDir Path dir;
  Simulation sim;
  app.radiance.sim.model.Person viewer;
  app.radiance.sim.model.Edge e;

  @org.junit.jupiter.api.AfterEach
  void close() {
    sim.close();
  }

  @BeforeEach
  void edge() {
    sim = new Simulation(Simulation.Options.of(42, 1, Persona.WARM, dir.resolve("w"))).run();
    var w = sim.engine.world();
    viewer = w.residents().getFirst();
    e = w.edge(viewer.id, w.residents().get(1).id);
    e.tags.clear();
    e.pending.clear();
    e.familiarity = 80;
  }

  @Test
  void aTagFlipsOnlyAfterHoldingForTwoDays() {
    e.affinity = 70; // fan's threshold is 50, plus the margin
    Impressions.retag(e, viewer, T);
    assertThat(e.tags).as("just crossed").doesNotContain("fan");
    Impressions.retag(e, viewer, T + DAY);
    assertThat(e.tags).as("a day in").doesNotContain("fan");
    Impressions.retag(e, viewer, T + 2 * DAY - 1);
    assertThat(e.tags).as("just short of two days").doesNotContain("fan");
    Impressions.retag(e, viewer, T + 2 * DAY);
    assertThat(e.tags).contains("fan");
  }

  @Test
  void droppingBackBeforeTheTwoDaysAreUpStartsTheClockAgain() {
    e.affinity = 70;
    Impressions.retag(e, viewer, T);
    e.affinity = 40;
    Impressions.retag(e, viewer, T + DAY);
    e.affinity = 70;
    Impressions.retag(e, viewer, T + 2 * DAY);
    assertThat(e.tags).as("the clock restarted a moment ago").doesNotContain("fan");
    Impressions.retag(e, viewer, T + 4 * DAY);
    assertThat(e.tags).contains("fan");
  }

  @Test
  void justPastTheThresholdIsntEnough_ThatIsWhatTheMarginIsFor() {
    e.affinity = 52;
    Impressions.retag(e, viewer, T);
    Impressions.retag(e, viewer, T + 10 * DAY);
    assertThat(e.tags).doesNotContain("fan");
  }

  @Test
  void aTagIsKeptThroughADipInsideTheMarginAndLostOnlyAfterTwoDaysBelowIt() {
    e.tags.add("fan");
    e.affinity = 47; // below the threshold, inside the margin
    Impressions.retag(e, viewer, T);
    Impressions.retag(e, viewer, T + 10 * DAY);
    assertThat(e.tags).as("no flapping around the line").contains("fan");

    e.affinity = 20;
    Impressions.retag(e, viewer, T + 11 * DAY);
    Impressions.retag(e, viewer, T + 12 * DAY);
    assertThat(e.tags).as("a day below").contains("fan");
    Impressions.retag(e, viewer, T + 13 * DAY);
    assertThat(e.tags).doesNotContain("fan");
  }
}
