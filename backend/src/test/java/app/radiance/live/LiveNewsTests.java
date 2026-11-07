package app.radiance.live;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.OfflineGateway;
import app.radiance.narrative.FakeRender;
import app.radiance.news.Headline;
import app.radiance.news.HeadlineFeed;
import app.radiance.news.HeadlineRepository;
import app.radiance.persistence.TestDatabase;
import app.radiance.persistence.UnitOfWork;
import app.radiance.graph.Onboarding;
import app.radiance.sim.Knobs;
import app.radiance.sim.UserActions;
import app.radiance.sim.WorldEngine;
import app.radiance.weather.FakeWeather;
import app.radiance.weather.WeatherHour;
import app.radiance.weather.WeatherRepository;
import app.radiance.weather.WeatherSources;
import app.radiance.world.genesis.SeedImporter;
import app.radiance.world.genesis.SkeletonGenerator;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** News and weather during a session: refreshed hourly, with takes on what broke since the batch (SPEC §5.12). */
class LiveNewsTests {

  static final ContentLibrary LIB = ClasspathContent.load(List.of(
      new ContentProperties.BlacklistEntry("pregnancy", List.of("pregnant"))));
  static final long HOUR = 3_600_000L;

  @TempDir Path dir;
  TestDatabase db;
  WorldEngine engine;
  LivePlay live;
  final AtomicInteger newsRefreshes = new AtomicInteger();
  final AtomicInteger weatherCalls = new AtomicInteger();
  volatile boolean weatherFails;

  @BeforeEach
  void world() {
    db = TestDatabase.leased(dir);
    var seed = SkeletonGenerator.generate(LIB, new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
    new SeedImporter(db.sql, db.lane, LIB).importSeed(seed, db.clock.nowMs());
    var fakes = new FakeResponses();
    FakeRender.register(fakes);
    LlmGateway gateway = OfflineGateway.create(fakes, app.radiance.kernel.WallClock.system());
    new Onboarding(db.sql, db.lane, gateway, LIB, 1.0).onboard(new Onboarding.Profile("Sam Rivera", "Engineer, runner.",
        "Acme", "Logan Square", List.of("softball"), List.of("tech", "fitness"), Onboarding.Backstory.LOCAL), 7,
        db.clock.nowMs());
    engine = new WorldEngine(db.sql, db.lane, LIB, Knobs.defaults());
    var backfill = new LivePlay.Backfill() {
      @Override
      public boolean running() {
        return false;
      }

      @Override
      public void start() {}
    };
    WeatherSources weather = (s, zone) -> {
      weatherCalls.incrementAndGet();
      if (weatherFails) throw new IllegalStateException("no weather today");
      return new FakeWeather(s, zone);
    };
    live = new LivePlay(db.sql, db.lane, engine, new UserActions(db.sql, db.lane, engine),
        new Appraiser(gateway, Duration.ofSeconds(5)), gateway, db.clock, db.holder, backfill, "Radiance", true,
        Duration.ofMinutes(10)).manualLoop().withNews(weather, (s, zone) -> new HeadlineFeed() {
          @Override
          public void refresh(long wallMs) {
            newsRefreshes.incrementAndGet();
          }

          @Override
          public List<Headline> on(LocalDate date) {
            return List.of();
          }
        });
  }

  @AfterEach
  void close() throws Exception {
    live.stop();
    assertThat(live.awaitIdle(Duration.ofSeconds(20))).isTrue();
    db.close();
  }

  /** A big story in every region, so whichever region a take draws has something to take on. */
  List<Headline> broke(long at) {
    var rows = new ArrayList<Headline>();
    for (String region : List.of("NATIONAL", "WORLD", "LOCAL")) {
      rows.add(new Headline("h_" + region, "https://example.com/" + region, "Wire", "Big " + region + " story", null, at, at,
          region, List.of("politics", "local", "economy", "sports", "tech"), 0.9, 1.0, "NORMAL",
          Map.of("economic", 0.5, "social", -0.5), at));
    }
    db.lane.run(() -> db.sql.commit(new HeadlineRepository(db.sql).save(new UnitOfWork(), rows)));
    return rows;
  }

  long count(String sql) {
    return db.sql.queryOne(sql, List.of(), r -> r.lng("n")).orElseThrow();
  }

  void waitFor(java.util.function.BooleanSupplier done) throws InterruptedException {
    for (int i = 0; i < 200 && !done.getAsBoolean(); i++) Thread.sleep(25);
    assertThat(done.getAsBoolean()).isTrue();
  }

  @Test
  void refreshesAtTheStartOfASessionAndThenHourly() throws Exception {
    live.touch();
    live.tick();
    waitFor(() -> newsRefreshes.get() == 1);
    assertThat(weatherCalls.get()).isEqualTo(1);

    db.clock.advance(Duration.ofMinutes(5));
    live.touch();
    live.tick();
    Thread.sleep(100);
    assertThat(newsRefreshes.get()).as("not again within the hour").isEqualTo(1);

    for (int i = 0; i < 12; i++) {
      db.clock.advance(Duration.ofMinutes(5));
      live.touch();
      live.tick();
    }
    waitFor(() -> newsRefreshes.get() == 2);
    assertThat(weatherCalls.get()).isEqualTo(2);
  }

  @Test
  void aFailedRefreshDoesntStopTheLoopAndTriesAgainInAnHour() throws Exception {
    weatherFails = true;
    live.touch();
    live.tick();
    waitFor(() -> weatherCalls.get() == 1);
    Thread.sleep(50);
    weatherFails = false;
    for (int i = 0; i < 13; i++) {
      db.clock.advance(Duration.ofMinutes(5));
      live.touch();
      live.tick();
    }
    waitFor(() -> newsRefreshes.get() == 1);
    assertThat(weatherCalls.get()).isEqualTo(2);
  }

  @Test
  void aRefreshStoresTheWeather() {
    live.touch();
    long t = db.clock.nowMs();
    live.refreshNow(t);
    long top = t - t % HOUR;
    assertThat(new WeatherRepository(db.sql).between(top, top + HOUR)).extracting(WeatherHour::hour).containsExactly(top);
  }

  @Test
  void onlineResidentsTakeOnWhatBrokeAndItAirs() throws Exception {
    live.touch();
    long t = db.clock.nowMs();
    broke(t - HOUR);

    live.refreshNow(t);

    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE headline_id IS NOT NULL")).isBetween(1L, 2L);
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE headline_id IS NOT NULL AND air_status = 'AIRED'"))
        .as("nothing shows before its time").isZero();
    long posts = count("SELECT COUNT(*) AS n FROM post WHERE headline_id IS NOT NULL");

    waitFor(() -> count("SELECT COUNT(*) AS n FROM post WHERE headline_id IS NOT NULL AND body IS NOT NULL") == posts);
    db.clock.advance(Duration.ofHours(2));
    engine.advanceTo(db.clock.nowMs());
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE headline_id IS NOT NULL AND air_status = 'AIRED'")).isEqualTo(posts);
  }

  @Test
  void aResidentDoesntTakeOnTheSameStoryTwice() throws Exception {
    live.touch();
    long t = db.clock.nowMs();
    broke(t - HOUR);
    for (int i = 0; i < 6; i++) live.refreshNow(t);
    Thread.sleep(200);
    long distinct = count("SELECT COUNT(*) AS n FROM (SELECT DISTINCT author_id, headline_id FROM post WHERE headline_id IS NOT NULL)");
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE headline_id IS NOT NULL")).isEqualTo(distinct);
  }

  @Test
  void headlinesFromBeforeTheBatchAreLeftToTheScript() {
    live.touch();
    long t = db.clock.nowMs();
    broke(t - 3 * HOUR);
    db.lane.run(() -> db.sql.commit(new UnitOfWork().add(
        "INSERT INTO batch_run (id, kind, started_at, status) VALUES ('b', 'NIGHTLY', ?, 'DONE')", t - HOUR)));
    live.refreshNow(t);
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE headline_id IS NOT NULL")).isZero();
  }

  /** Drop-in mode (ADR 0024): the login brings the day's news, and a handful of residents take it on within minutes. */
  @Test
  void aDropInLoginBringsANewsStormThatLandsWithinMinutes() throws Exception {
    live.dropIn(true);
    live.touch();
    long t = db.clock.nowMs();
    broke(t - 10 * HOUR); // older than an ordinary session looks, inside the day a drop-in looks at
    live.refreshNow(t);
    long storm = count("SELECT COUNT(*) AS n FROM post WHERE headline_id IS NOT NULL");
    assertThat(storm).as("more than an ordinary refresh's two").isGreaterThan(2L);
    long last = db.sql.queryOne("SELECT MAX(created_at) AS m FROM post WHERE headline_id IS NOT NULL", List.of(), r -> r.lng("m")).orElseThrow();
    assertThat(last - t).as("all of it within the first visit").isLessThanOrEqualTo(8 * 60_000L);

    live.refreshNow(t + 1_000);
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE headline_id IS NOT NULL") - storm).as("only the login storms").isLessThanOrEqualTo(2L);
  }
}
