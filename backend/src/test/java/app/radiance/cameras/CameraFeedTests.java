package app.radiance.cameras;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.kernel.WallClock;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The feed over real HTTP, against a local server that answers as IDOT's does (checked 2026-11-02). */
class CameraFeedTests {

  static final long NOW = Instant.parse("2026-11-02T15:00:00Z").toEpochMilli();
  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

  HttpServer server;
  String origin;
  final AtomicLong now = new AtomicLong(NOW);
  final AtomicInteger listCalls = new AtomicInteger();
  final AtomicInteger imageCalls = new AtomicInteger();
  volatile String list;
  volatile int imageStatus = 200;
  volatile String imageType = "image/jpeg";
  volatile String lastQuery;
  final WallClock clock = now::get;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/query", ex -> {
      listCalls.incrementAndGet();
      lastQuery = ex.getRequestURI().getQuery();
      respond(ex, 200, "application/json", list.getBytes(StandardCharsets.UTF_8), null);
    });
    server.createContext("/snapshots", ex -> {
      imageCalls.incrementAndGet();
      String taken = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now.get() - 120_000).atOffset(ZoneOffset.UTC));
      respond(ex, imageStatus, imageType, JPEG, taken);
    });
    server.start();
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
    list = rows(row("roof_1.jpg", 4, false), row("lake_2.jpg", 9, false));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  static void respond(HttpExchange ex, int status, String type, byte[] body, String modified) throws IOException {
    ex.getResponseHeaders().set("Content-Type", type);
    if (modified != null) ex.getResponseHeaders().set("Last-Modified", modified);
    ex.sendResponseHeaders(status, body.length);
    ex.getResponseBody().write(body);
    ex.close();
  }

  String row(String file, int age, boolean tooOld) {
    return row(origin + "/snapshots/" + file, age, tooOld, true);
  }

  String row(String url, int age, boolean tooOld, boolean ignored) {
    return "{\"attributes\":{\"SnapShot\":\"" + url + "\",\"AgeInMinutes\":" + age + ",\"TooOld\":" + tooOld + "}}";
  }

  static String rows(String... rows) {
    return "{\"features\":[" + String.join(",", rows) + "]}";
  }

  CameraFeed feed(boolean enabled) {
    return new CameraFeed(enabled, URI.create(origin + "/query"), origin,
        List.of(new CameraFeed.Spot("roof", "UIC Roof", "roof_"), new CameraFeed.Spot("lake", "The Lake", "lake_"),
            new CameraFeed.Spot("gone", "Gone", "gone_")), clock);
  }

  @Test
  void listsThePickedCamerasThatAreUp_InOrder_WithTheirAge() {
    var cameras = feed(true).cameras();
    assertThat(cameras).extracting(CameraFeed.Camera::id).containsExactly("roof", "lake");
    assertThat(cameras.getFirst().name()).isEqualTo("UIC Roof");
    assertThat(cameras.getFirst().takenAt()).isEqualTo(NOW - 4 * 60_000);
    assertThat(lastQuery).contains("SnapShot+LIKE+'%roof_%'", "outFields=SnapShot,AgeInMinutes,TooOld");
  }

  @Test
  void leavesOutWhatTheSourceCallsTooOld() {
    list = rows(row("roof_1.jpg", 400, true), row("lake_2.jpg", 9, false));
    assertThat(feed(true).cameras()).extracting(CameraFeed.Camera::id).containsExactly("lake");
  }

  @Test
  void neverConnectsAnywhereButTheConfiguredOrigin() {
    list = rows(row("https://evil.example/snapshots/roof_1.jpg", 1, false, true),
        row("http://169.254.169.254/latest/roof_2.jpg", 1, false, true),
        row(origin.replace("127.0.0.1", "user@127.0.0.1") + "/snapshots/roof_3.jpg", 1, false, true),
        row("not a url roof_4", 1, false, true),
        row(origin.replace("http:", "https:") + "/snapshots/roof_5.jpg", 1, false, true));
    var feed = feed(true);
    assertThat(feed.cameras()).isEmpty();
    assertThat(feed.image("roof")).isEmpty();
    assertThat(imageCalls.get()).isZero();
  }

  @Test
  void onlyAPickedCameraCanBeAskedFor() {
    var feed = feed(true);
    assertThat(feed.image("../../etc/passwd")).isEmpty();
    assertThat(feed.image("gone")).isEmpty();
    assertThat(imageCalls.get()).isZero();
  }

  @Test
  void anImageIsFetchedAtMostOnceAMinuteAndItsOwnClockBecomesItsAge() {
    var feed = feed(true);
    var first = feed.image("roof").orElseThrow();
    assertThat(first.jpeg()).isEqualTo(JPEG);
    assertThat(first.takenAt()).isEqualTo(NOW - 120_000);
    assertThat(feed.cameras().getFirst().takenAt()).as("the image knows better than the list").isEqualTo(NOW - 120_000);

    now.addAndGet(59_000);
    feed.image("roof");
    assertThat(imageCalls.get()).isEqualTo(1);
    now.addAndGet(1_000);
    feed.image("roof");
    assertThat(imageCalls.get()).isEqualTo(2);
  }

  @Test
  void theListIsAskedForEveryFewMinutes_AndAFailureKeepsTheLastOne() {
    var feed = feed(true);
    feed.cameras();
    feed.cameras();
    assertThat(listCalls.get()).isEqualTo(1);
    list = "not json";
    now.addAndGet(CameraFeed.LIST_EVERY);
    assertThat(feed.cameras()).as("still the last good list").hasSize(2);
    assertThat(listCalls.get()).isEqualTo(2);
    feed.cameras();
    assertThat(listCalls.get()).as("a failure doesn't mean asking again at once").isEqualTo(2);
  }

  @Test
  void aFailingCameraKeepsItsLastPictureForAWhile_ThenGoesAway() {
    var feed = feed(true);
    feed.image("roof").orElseThrow();
    imageStatus = 500;
    now.addAndGet(CameraFeed.IMAGE_EVERY);
    assertThat(feed.image("roof")).isPresent();
    now.addAndGet(CameraFeed.KEEP_FAILED_FOR);
    assertThat(feed.image("roof")).isEmpty();
  }

  @Test
  void somethingThatIsntAJpegIsRefused() {
    imageType = "text/html";
    assertThat(feed(true).image("roof")).isEmpty();
  }

  @Test
  void aDeadSourceJustMeansNoCameras() {
    server.stop(0);
    var feed = feed(true);
    assertThat(feed.cameras()).isEmpty();
    assertThat(feed.image("roof")).isEmpty();
  }

  @Test
  void offMeansNoCallsAtAll() {
    var feed = feed(false);
    assertThat(feed.cameras()).isEmpty();
    assertThat(feed.image("roof")).isEmpty();
    assertThat(listCalls.get() + imageCalls.get()).isZero();
  }
}
