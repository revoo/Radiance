package app.radiance.batch;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.llm.BudgetExhaustedException;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.LlmRequest;
import app.radiance.llm.LlmResult;
import app.radiance.llm.Purpose;
import app.radiance.narrative.HeadlineClassifier;
import app.radiance.narrative.RealHeadlines;
import app.radiance.news.HeadlineFeeds;
import app.radiance.news.HeadlineIngest;
import app.radiance.news.RssFeeds;
import app.radiance.persistence.TestDatabase;
import app.radiance.sim.ContinuityAuditor;
import app.radiance.sim.Knobs;
import app.radiance.sim.ScriptWriter;
import app.radiance.sim.WorldEngine;
import app.radiance.weather.FakeWeather;
import app.radiance.world.genesis.SeedImporter;
import app.radiance.world.genesis.SkeletonGenerator;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real path for news: a feed over real HTTP, through ingest and classification, into a night's script. */
class RealHeadlinesBatchTests {

  static final long HOUR = 3_600_000L;

  @TempDir Path dir;
  TestDatabase db;
  WorldEngine engine;
  ScriptWriter writer;
  HttpServer server;
  volatile String feed = "<rss><channel></channel></rss>";
  final AtomicInteger classifyCalls = new AtomicInteger();
  final AtomicBoolean budgetOut = new AtomicBoolean();

  @BeforeEach
  void start() throws Exception {
    db = TestDatabase.leased(dir);
    var seed = SkeletonGenerator.generate(NightlyBatchTests.LIB, new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
    new SeedImporter(db.sql, db.lane, NightlyBatchTests.LIB).importSeed(seed, db.clock.nowMs());
    engine = new WorldEngine(db.sql, db.lane, NightlyBatchTests.LIB, Knobs.defaults());
    writer = new ScriptWriter(db.sql, db.lane, engine, ContinuityAuditor.Mode.THROW, db.clock);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/feed", ex -> {
      byte[] bytes = feed.getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, bytes.length);
      ex.getResponseBody().write(bytes);
      ex.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    db.close();
  }

  NightlyBatch batch() {
    var offline = NightlyBatchTests.offline();
    LlmGateway gateway = new NightlyBatchTests.Delegating(offline) {
      @Override
      public <T> LlmResult<T> call(LlmRequest<T> r) {
        if (r.purpose() == Purpose.HEADLINE_CLASSIFY) {
          if (budgetOut.get()) throw new BudgetExhaustedException("usd:daily", db.clock.nowMs() + HOUR);
          classifyCalls.incrementAndGet();
        }
        return offline.call(r);
      }
    };
    var sources = List.of(new RssFeeds.Source("Test Wire", "http://127.0.0.1:" + server.getAddress().getPort() + "/feed"));
    HeadlineFeeds feeds = (seed, zone) -> new RealHeadlines(db.sql, new HeadlineIngest(db.sql, db.lane, new RssFeeds(), sources),
        new HeadlineClassifier(db.sql, db.lane, NightlyBatchTests.LIB, gateway), zone);
    return new NightlyBatch(db.sql, db.admin, db.lane, db.holder, db.clock, NightlyBatchTests.LIB, engine, writer, gateway,
        "Radiance", NightlyBatchTests.CRONS, true, FakeWeather::new, feeds);
  }

  /** {@code n} stories, published in the hours before now. */
  String stories(String prefix, int n) {
    var sb = new StringBuilder("<rss><channel>");
    for (int i = 0; i < n; i++) {
      var at = Instant.ofEpochMilli(db.clock.nowMs() - (i % 6 + 1) * HOUR).atZone(ZoneOffset.UTC);
      sb.append("<item><title>").append(prefix).append(" story number ").append(i).append(" in Chicago, ").append(i * 7)
          .append("</title><link>https://example.com/").append(prefix).append('/').append(i).append("</link><pubDate>")
          .append(DateTimeFormatter.RFC_1123_DATE_TIME.format(at)).append("</pubDate></item>");
    }
    return sb.append("</channel></rss>").toString();
  }

  long count(String sql) {
    return db.sql.queryOne(sql, List.of(), r -> r.lng("n")).orElseThrow();
  }

  @Test
  void aNightsScriptUsesTheHeadlinesFromTheFeeds() {
    feed = stories("first", 12);
    var report = batch().run(NightlyBatch.Kind.NIGHTLY);

    assertThat(report.status()).isEqualTo("DONE");
    assertThat(count("SELECT COUNT(*) AS n FROM headline WHERE classified_at IS NOT NULL AND url LIKE 'https://example.com/first/%'"))
        .isEqualTo(12);
    assertThat(count("SELECT COUNT(*) AS n FROM headline WHERE url LIKE 'fixture://%'")).as("no fixtures").isZero();
    assertThat(count("SELECT COUNT(*) AS n FROM post p JOIN headline h ON h.id = p.headline_id"
        + " WHERE h.url LIKE 'https://example.com/first/%'")).as("takes on the real stories").isPositive();
  }

  @Test
  void onlyEachFeedsTopStoriesAreKept() {
    feed = stories("many", 30);
    batch().run(NightlyBatch.Kind.NIGHTLY);
    assertThat(count("SELECT COUNT(*) AS n FROM headline")).isEqualTo(12);
  }

  @Test
  void aStorySeenBeforeIsNotStoredOrPaidForTwice() {
    feed = stories("same", 12);
    batch().run(NightlyBatch.Kind.NIGHTLY);
    int calls = classifyCalls.get();
    assertThat(calls).isEqualTo(1);

    db.clock.set(db.clock.nowMs() + 2 * HOUR);
    batch().run(NightlyBatch.Kind.NIGHTLY);
    assertThat(classifyCalls.get()).isEqualTo(calls);
    assertThat(count("SELECT COUNT(*) AS n FROM headline")).isEqualTo(12);
  }

  @Test
  void whenABudgetRunsOutHeadlinesWaitAndTheNightStillFinishes() {
    feed = stories("late", 12);
    budgetOut.set(true);
    assertThat(batch().run(NightlyBatch.Kind.NIGHTLY).status()).isEqualTo("DONE");
    assertThat(count("SELECT COUNT(*) AS n FROM headline WHERE classified_at IS NULL")).isEqualTo(12);
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE headline_id IS NOT NULL")).as("nothing to take on yet").isZero();

    budgetOut.set(false);
    db.clock.set(db.clock.nowMs() + HOUR);
    batch().run(NightlyBatch.Kind.NIGHTLY);
    assertThat(count("SELECT COUNT(*) AS n FROM headline WHERE classified_at IS NULL")).isZero();
  }

  @Test
  void aDeadFeedLeavesTheNightWithoutNewsButFinished() {
    server.stop(0);
    assertThat(batch().run(NightlyBatch.Kind.NIGHTLY).status()).isEqualTo("DONE");
    assertThat(count("SELECT COUNT(*) AS n FROM headline")).isZero();
  }
}
