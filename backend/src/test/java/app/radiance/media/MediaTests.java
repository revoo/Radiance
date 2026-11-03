package app.radiance.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.radiance.backup.S3Client;
import app.radiance.persistence.TestDatabase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Storage, the generators and the upload flow, over real files and real HTTP. */
class MediaTests {

  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
  static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1};
  static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 1};
  static final byte[] SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
  static final byte[] HTML = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

  @TempDir Path dir;

  // ---------------------------------------------------------------------------------- what a file really is

  @Test
  void aFilesTypeComesFromItsBytes() {
    assertThat(ImageTypes.sniff(JPEG)).contains("image/jpeg");
    assertThat(ImageTypes.sniff(PNG)).contains("image/png");
    assertThat(ImageTypes.sniff(WEBP)).contains("image/webp");
    assertThat(ImageTypes.sniff(SVG)).isEmpty();
    assertThat(ImageTypes.sniff(HTML)).isEmpty();
    assertThat(ImageTypes.sniff(new byte[0])).isEmpty();
    assertThat(ImageTypes.sniff(new byte[] {(byte) 0xFF})).isEmpty();
  }

  // ---------------------------------------------------------------------------------- storage

  @Test
  void localFilesRoundTripAndAMissingOneIsEmpty() throws Exception {
    var store = new LocalFileMediaStore(dir);
    store.put("uploads/a-full", JPEG);
    assertThat(store.get("uploads/a-full")).hasValue(JPEG);
    assertThat(store.get("uploads/nope")).isEmpty();
  }

  @Test
  void aKeyCantLeaveTheMediaFolder() {
    var store = new LocalFileMediaStore(dir.resolve("media"));
    assertThatThrownBy(() -> store.put("../outside", JPEG)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.get("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    assertThat(Files.exists(dir.resolve("outside"))).isFalse();
  }

  @Test
  void overwritingLeavesNoTempFilesBehind() throws Exception {
    var store = new LocalFileMediaStore(dir);
    store.put("k", JPEG);
    store.put("k", PNG);
    assertThat(store.get("k")).hasValue(PNG);
    try (var files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString())).containsExactly("k");
    }
  }

  /** Just enough of an S3 server for the store: it doesn't check signatures. BackupTests and the smoke test do. */
  static final class FakeR2 implements AutoCloseable {
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final HttpServer server;

    FakeR2() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", ex -> {
        String key = ex.getRequestURI().getPath().replaceFirst("^/bucket/", "");
        switch (ex.getRequestMethod()) {
          case "PUT" -> {
            objects.put(key, ex.getRequestBody().readAllBytes());
            respond(ex, 200, new byte[0]);
          }
          case "HEAD" -> {
            if (objects.containsKey(key)) ex.getResponseHeaders().set("Content-Length", String.valueOf(objects.get(key).length));
            ex.sendResponseHeaders(objects.containsKey(key) ? 200 : 404, -1);
            ex.close();
          }
          default -> {
            var body = objects.get(key);
            respond(ex, body == null ? 404 : 200, body == null ? new byte[0] : body);
          }
        }
      });
      server.start();
    }

    S3Client client() {
      return new S3Client(new S3Client.Settings(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "bucket", "k", "s", "auto"));
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  @Test
  void r2KeepsACopy_AndARestoredServerFillsItsDiskFromIt() throws Exception {
    try (var r2 = new FakeR2()) {
      var store = new R2BackedMediaStore(new LocalFileMediaStore(dir.resolve("a")), r2.client());
      store.put("uploads/x", JPEG);
      assertThat(r2.objects.get("media/uploads/x")).isEqualTo(JPEG);
      assertThat(store.get("uploads/x")).hasValue(JPEG);

      // a new server: an empty disk, the same bucket
      var emptyDisk = new LocalFileMediaStore(dir.resolve("b"));
      var restored = new R2BackedMediaStore(emptyDisk, r2.client());
      assertThat(emptyDisk.get("uploads/x")).isEmpty();
      assertThat(restored.get("uploads/x")).hasValue(JPEG);
      assertThat(emptyDisk.get("uploads/x")).as("now it's on disk too").hasValue(JPEG);
      assertThat(restored.get("uploads/nope")).isEmpty();
    }
  }

  @Test
  void r2StoresPhotosWhereBackupRetentionNeverLooks() {
    assertThat(R2BackedMediaStore.PREFIX).isEqualTo("media/");
    assertThat(app.radiance.backup.BackupService.REMOTE_PREFIX).isNotEqualTo(R2BackedMediaStore.PREFIX);
  }

  // ---------------------------------------------------------------------------------- the placeholder

  @Test
  void thePlaceholderIsAnSvgThatEscapesItsLabel_AndASeedAlwaysLooksTheSame() {
    var fake = new FakeImageGenerator();
    var a = fake.generate("a <script>alert('x')</script> & friends", 7);
    var svg = new String(a.bytes(), StandardCharsets.UTF_8);
    assertThat(a.mime()).isEqualTo(FakeImageGenerator.SVG);
    assertThat(svg).doesNotContain("<script>").contains("&lt;script&gt;", "&amp; friends");
    assertThat(fake.generate("a <script>alert('x')</script> & friends", 7).bytes()).isEqualTo(a.bytes());
    assertThat(new String(fake.generate("something else", 7).bytes(), StandardCharsets.UTF_8))
        .contains(svg.substring(svg.indexOf("hsl("), svg.indexOf("hsl(") + 12));
    assertThat(fake.real()).isFalse();
  }

  @Test
  void aLongPromptIsCutShort_AndNeverLeaksPastTheLabel() {
    var svg = new String(new FakeImageGenerator().generate("x".repeat(500), 1).bytes(), StandardCharsets.UTF_8);
    assertThat(svg).doesNotContain("x".repeat(100)).contains("…");
  }

  // ---------------------------------------------------------------------------------- Workers AI

  static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
    ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) ex.getResponseBody().write(body);
    ex.close();
  }

  WorkersAiImageGenerator workers(HttpServer server) {
    return new WorkersAiImageGenerator(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "acct", "@cf/model/x", "secret");
  }

  HttpServer server(int status, String body, java.util.List<String> seen, AtomicInteger calls) throws IOException {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      calls.incrementAndGet();
      seen.add(ex.getRequestURI().getPath());
      seen.add(ex.getRequestHeaders().getFirst("Authorization"));
      seen.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(ex, status, body.getBytes(StandardCharsets.UTF_8));
    });
    server.start();
    return server;
  }

  /** Regression: the real API answers 400 to a {@code seed}, so every picture would have failed. Found on the first real call. */
  @Test
  void workersAiSendsOnlyWhatTheModelAccepts_AndDecodesTheImage() throws Exception {
    var seen = new java.util.ArrayList<String>();
    var image = Base64.getEncoder().encodeToString(JPEG);
    var server = server(200, "{\"success\":true,\"result\":{\"image\":\"" + image + "\"}}", seen, new AtomicInteger());
    try {
      var out = workers(server).generate("a red car", 1234);
      assertThat(out.bytes()).isEqualTo(JPEG);
      assertThat(out.mime()).isEqualTo("image/jpeg");
      assertThat(seen.get(0)).isEqualTo("/client/v4/accounts/acct/ai/run/@cf/model/x");
      assertThat(seen.get(1)).isEqualTo("Bearer secret");
      assertThat(seen.get(2)).contains("\"prompt\":\"a red car\"", "\"steps\":4").doesNotContain("seed");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void beingOutOfAllowanceIsAQuotaError_AndAnythingElseIsAFailure() throws Exception {
    for (var c : java.util.List.of(new Object[] {429, "slow down"}, new Object[] {402, "pay"}, new Object[] {400, "you have used your daily free allocation of 10,000 neurons"})) {
      var server = server((Integer) c[0], (String) c[1], new java.util.ArrayList<>(), new AtomicInteger());
      try {
        assertThatThrownBy(() -> workers(server).generate("x", 1)).isInstanceOf(ImageGenerator.QuotaExceeded.class);
      } finally {
        server.stop(0);
      }
    }
    var broken = server(500, "oops", new java.util.ArrayList<>(), new AtomicInteger());
    try {
      assertThatThrownBy(() -> workers(broken).generate("x", 1)).isInstanceOf(IllegalStateException.class).isNotInstanceOf(ImageGenerator.QuotaExceeded.class);
    } finally {
      broken.stop(0);
    }
  }

  @Test
  void aResponseThatIsntAnImageIsRefused() throws Exception {
    var notImage = Base64.getEncoder().encodeToString(HTML);
    for (String body : java.util.List.of("{\"result\":{}}", "{\"result\":{\"image\":\"%%%\"}}", "{\"result\":{\"image\":\"" + notImage + "\"}}")) {
      var server = server(200, body, new java.util.ArrayList<>(), new AtomicInteger());
      try {
        assertThatThrownBy(() -> workers(server).generate("x", 1)).isInstanceOf(RuntimeException.class);
      } finally {
        server.stop(0);
      }
    }
  }

  // ---------------------------------------------------------------------------------- uploads

  Uploads uploads(TestDatabase db) {
    return new Uploads(db.sql, db.lane, new LocalFileMediaStore(dir.resolve("media")), db.clock);
  }

  @Test
  void anUploadTakesBothSizesThenBecomesReady() throws Exception {
    var db = TestDatabase.leased(dir.resolve("db"));
    try {
      var uploads = uploads(db);
      var row = uploads.start("image/jpeg", JPEG.length, 1600, 1200);
      assertThat(row.status()).isEqualTo(MediaRow.PENDING);
      uploads.receive(row.id(), false, JPEG);
      assertThatThrownBy(() -> uploads.complete(row.id())).as("the thumbnail is missing").isInstanceOf(Uploads.Rejected.class);
      uploads.receive(row.id(), true, JPEG);
      var ready = uploads.complete(row.id());
      assertThat(ready.status()).isEqualTo(MediaRow.READY);
      assertThat(ready.mime()).isEqualTo("image/jpeg");
      assertThat(new MediaRepository(db.sql).byId(row.id()).orElseThrow().status()).isEqualTo(MediaRow.READY);
      assertThatThrownBy(() -> uploads.receive(row.id(), false, PNG)).as("it can't be swapped afterwards").isInstanceOf(Uploads.Rejected.class);
    } finally {
      db.close();
    }
  }

  @Test
  void theTypeSavedIsWhatTheBytesAre_NotWhatTheClientClaimed() throws Exception {
    var db = TestDatabase.leased(dir.resolve("db"));
    try {
      var uploads = uploads(db);
      var row = uploads.start("image/jpeg", PNG.length, null, null);
      uploads.receive(row.id(), false, PNG);
      uploads.receive(row.id(), true, PNG);
      assertThat(uploads.complete(row.id()).mime()).isEqualTo("image/png");
    } finally {
      db.close();
    }
  }

  @Test
  void anythingThatIsntAPhotoIsRefused() throws Exception {
    var db = TestDatabase.leased(dir.resolve("db"));
    try {
      var uploads = uploads(db);
      for (String mime : new String[] {"image/svg+xml", "text/html", "application/pdf", null}) {
        assertThatThrownBy(() -> uploads.start(mime, 10, null, null)).isInstanceOf(Uploads.Rejected.class);
      }
      assertThatThrownBy(() -> uploads.start("image/jpeg", 0, null, null)).isInstanceOf(Uploads.Rejected.class);
      assertThatThrownBy(() -> uploads.start("image/jpeg", Uploads.MAX_FULL + 1L, null, null)).isInstanceOf(Uploads.Rejected.class);
      var row = uploads.start("image/jpeg", 10, null, null);
      assertThatThrownBy(() -> uploads.receive(row.id(), false, SVG)).as("SVG can carry scripts").isInstanceOf(Uploads.Rejected.class);
      assertThatThrownBy(() -> uploads.receive(row.id(), false, HTML)).isInstanceOf(Uploads.Rejected.class);
      assertThatThrownBy(() -> uploads.receive(row.id(), false, new byte[0])).isInstanceOf(Uploads.Rejected.class);
      byte[] big = new byte[Uploads.MAX_THUMB + 1];
      System.arraycopy(JPEG, 0, big, 0, JPEG.length);
      assertThatThrownBy(() -> uploads.receive(row.id(), true, big)).as("over the thumbnail's cap").isInstanceOf(Uploads.Rejected.class);
      assertThatThrownBy(() -> uploads.receive("nope", false, JPEG)).isInstanceOf(Uploads.Rejected.class);
    } finally {
      db.close();
    }
  }
}
