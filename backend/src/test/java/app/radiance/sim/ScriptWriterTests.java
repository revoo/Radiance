package app.radiance.sim;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.news.FixtureHeadlines;
import app.radiance.news.HeadlineSelection;
import app.radiance.persistence.TestDatabase;
import app.radiance.weather.FakeWeather;
import app.radiance.weather.WeatherBook;
import app.radiance.world.genesis.SeedImporter;
import app.radiance.world.genesis.SkeletonGenerator;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Planning (SPEC §5.1): a day's script is planned, audited, written and airs; same seed, same hash. */
class ScriptWriterTests {

  static final ContentLibrary LIB = ClasspathContent.load(List.of(
      new ContentProperties.BlacklistEntry("pregnancy", List.of("pregnant"))));
  static final long T = TestDatabase.START;
  static final long DAY = 86_400_000L;

  @TempDir Path dir;

  record Run(ScriptWriter.Result result, WorldEngine.Report aired, TestDatabase db) {}

  Run day(Path where, ContentLibrary lib, int days) {
    var db = TestDatabase.leased(where);
    var seed = SkeletonGenerator.generate(lib, new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
    new SeedImporter(db.sql, db.lane, LIB).importSeed(seed, T); // the world started before any mid-world blacklist
    var engine = new WorldEngine(db.sql, db.lane, lib, Knobs.defaults());
    var zone = engine.world().zone;
    var weather = new WeatherBook(zone, lib.weather()).putAll(new FakeWeather(7, zone).hours(
        T - T % 3_600_000L - 8 * DAY, T + (days + 2) * DAY));
    var fixtures = new FixtureHeadlines(7, zone, 100);
    var headlines = new ArrayList<app.radiance.news.Headline>();
    for (int d = -1; d <= days; d++) {
      headlines.addAll(HeadlineSelection.select(fixtures.on(engine.world().date(T).plusDays(d)), 25,
          Knobs.defaults().regionMix()));
    }
    var writer = new ScriptWriter(db.sql, db.lane, engine, ContinuityAuditor.Mode.THROW);
    var result = writer.write(T, T + days * DAY, Fidelity.FULL, headlines, weather, Set.of(), ContentOrigin.SCRIPT);
    var aired = engine.advanceTo(T + days * DAY);
    return new Run(result, aired, db);
  }

  @Test
  void aFullDayIsPlannedAuditedAndAired() {
    var run = day(dir.resolve("a"), LIB, 2);
    try {
      var r = run.result();
      assertThat(r.findings()).isEmpty();
      assertThat(r.posts()).isBetween(15, 150);
      assertThat(r.comments()).isPositive();
      assertThat(r.reactions()).isPositive();
      assertThat(run.aired().aired()).isPositive();
      double dropped = run.aired().dropped() / (double) (run.aired().aired() + run.aired().dropped());
      assertThat(dropped).as("dropped at airing: " + run.aired().dropReasons()).isLessThan(0.05);
      assertThat(run.db().count("memory")).isPositive();
    } finally {
      run.db().close();
    }
  }

  @Test
  void theSameSeedGivesTheSamePlanHash() {
    var a = day(dir.resolve("a"), LIB, 1);
    var b = day(dir.resolve("b"), LIB, 1);
    try {
      assertThat(a.result().planHash()).isEqualTo(b.result().planHash());
      assertThat(a.result().posts()).isEqualTo(b.result().posts());
    } finally {
      a.db().close();
      b.db().close();
    }
  }

  @Test
  void anArcBlacklistedMidWorldTakesItsAbandonExit() {
    var lib = ClasspathContent.load(List.of(new ContentProperties.BlacklistEntry("pregnancy", List.of("pregnant")),
        new ContentProperties.BlacklistEntry("car_saga", List.of("zzz-never"))));
    var run = day(dir.resolve("c"), lib, 1);
    try {
      var engine = new WorldEngine(run.db().sql, run.db().lane, lib, Knobs.defaults());
      var saga = engine.world().arcs.values().stream().filter(x -> x.defId.equals("car_saga")).toList();
      assertThat(saga).isNotEmpty().allMatch(x -> !x.active() && x.status.name().equals("ABANDONED"));
    } finally {
      run.db().close();
    }
  }
}
