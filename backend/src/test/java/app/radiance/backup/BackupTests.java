package app.radiance.backup;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.kernel.ManualClock;
import app.radiance.persistence.TestDatabase;
import app.radiance.persistence.UnitOfWork;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Backups and restore (ADR 0019) on real SQLite files, against a real HTTP server standing in for R2
 * that checks every request's signature the way a store does.
 */
class BackupTests {

  static final String KEY = "test-access-key", SECRET = "test-secret-key";

  @TempDir Path dir;
  FakeS3 s3;

  @AfterEach
  void stop() {
    if (s3 != null) s3.close();
  }

  R2Settings r2() throws IOException {
    if (s3 == null) s3 = new FakeS3();
    return R2Settings.from(Map.of("R2_ENDPOINT", s3.url(), "R2_ACCESS_KEY_ID", KEY, "R2_SECRET_ACCESS_KEY", SECRET, "R2_BACKUP_BUCKET",
        "radiance-backups"));
  }

  static final R2Settings NO_KEYS = R2Settings.from(Map.of());

  BackupService service(Path db, R2Settings r2, ManualClock clock) {
    return new BackupService(db, db.resolveSibling("backups"), r2, 14, 8, 3, clock);
  }

  // ------------------------------------------------------------------------------- signing, retention

  /** AWS's published SigV4 examples for S3: if these pass, the signature is the one a store expects. */
  @Test
  void signaturesMatchAwssPublishedExamples() {
    var empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    var getObject = new TreeMap<>(Map.of("host", "examplebucket.s3.amazonaws.com", "range", "bytes=0-9", "x-amz-content-sha256", empty,
        "x-amz-date", "20130524T000000Z"));
    assertThat(S3Client.authorization("GET", "/test.txt", "", getObject, empty, "20130524T000000Z", "us-east-1", "AKIAIOSFODNN7EXAMPLE",
        "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")).endsWith(
            "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    var list = new TreeMap<>(Map.of("host", "examplebucket.s3.amazonaws.com", "x-amz-content-sha256", empty, "x-amz-date",
        "20130524T000000Z"));
    assertThat(S3Client.authorization("GET", "/", "max-keys=2&prefix=J", list, empty, "20130524T000000Z", "us-east-1",
        "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
        .endsWith("Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7");
  }

  @Test
  void queriesAreEncodedAndSortedTheWaySigningExpects() {
    assertThat(S3Client.canonicalQuery(Map.of("prefix", "backups/", "list-type", "2", "continuation-token", "a b+c")))
        .isEqualTo("continuation-token=a%20b%2Bc&list-type=2&prefix=backups%2F");
  }

  static String name(String isoInstant) {
    return "backups/" + Retention.fileName(Instant.parse(isoInstant));
  }

  @Test
  void retentionKeepsFourteenDaysThenEightWeeks() {
    var names = new ArrayList<String>();
    var start = Instant.parse("2026-11-02T08:30:00Z");
    for (int d = 0; d < 200; d++) names.add("backups/" + Retention.fileName(start.minusSeconds(d * 86_400L)));
    names.add("backups/not-a-backup.txt");
    var kept = new ArrayList<>(names);
    var doomed = Retention.toDelete(names, 14, 8);
    kept.removeAll(doomed);
    assertThat(kept).contains("backups/not-a-backup.txt");
    kept.remove("backups/not-a-backup.txt");
    assertThat(kept).hasSize(22).contains(names.getFirst());
    for (int d = 0; d < 14; d++) assertThat(kept).contains(names.get(d));
    // the rest are one a week, reaching back about eight weeks past the dailies
    var oldest = kept.stream().map(n -> Retention.takenAt(n).orElseThrow()).min(Instant::compareTo).orElseThrow();
    assertThat(oldest).isBetween(start.minusSeconds(70 * 86_400L), start.minusSeconds(50 * 86_400L));
    assertThat(doomed).doesNotContain("backups/not-a-backup.txt");
  }

  @Test
  void retentionKeepsOnlyTheNewestOfADayAndNeverTheLastOne() {
    var older = name("2026-11-02T03:00:00Z");
    var newer = name("2026-11-02T08:30:00Z");
    assertThat(Retention.toDelete(List.of(older, newer), 14, 8)).containsExactly(older);
    assertThat(Retention.toDelete(List.of(newer), 0, 0)).isEmpty();
    assertThat(Retention.newest(List.of(older, newer, "backups/other"))).contains(newer);
    assertThat(Retention.takenAt("backups/other")).isEmpty();
  }

  // ------------------------------------------------------------------------------- backing up

  @Test
  void aSnapshotIsConsistentWhileTheAppKeepsWriting() throws Exception {
    try (var db = TestDatabase.leased(dir)) {
      db.commit(new UnitOfWork().add("INSERT INTO session_log (id, started_at) VALUES ('before', 1)"));
      var stop = new AtomicBoolean();
      var writes = new AtomicInteger();
      var writer = Thread.ofVirtual().start(() -> {
        while (!stop.get()) {
          int i = writes.incrementAndGet();
          db.commit(new UnitOfWork().add("INSERT INTO session_log (id, started_at) VALUES (?, ?)", "w" + i, i));
        }
      });
      var r = service(dir.resolve("radiance.db"), r2(), db.clock).run();
      stop.set(true);
      writer.join();
      assertThat(r.outcome()).isEqualTo(BackupService.Outcome.DONE);
      assertThat(r.offsiteKey()).startsWith("backups/radiance-");
      assertThat(s3.objects).containsKey("radiance-backups/" + r.offsiteKey());

      // the restored copy is a healthy database with what was committed before the snapshot
      Path other = dir.resolve("elsewhere/radiance.db");
      assertThat(restore(other, r2(), "latest")).isEqualTo(Restore.OK);
      BackupService.verify(other);
      assertThat(count(other, "SELECT COUNT(*) FROM session_log WHERE id = 'before'")).isEqualTo(1);
    }
  }

  @Test
  void withoutKeysTheSnapshotStaysLocalAndEveryoneIsTold() throws Exception {
    try (var db = TestDatabase.leased(dir)) {
      var svc = service(dir.resolve("radiance.db"), NO_KEYS, db.clock);
      var r = svc.run();
      assertThat(r.outcome()).isEqualTo(BackupService.Outcome.LOCAL_ONLY);
      assertThat(svc.localBackups()).hasSize(1);
      assertThat(svc.missingKeysBanner()).contains("BACKUPS ARE NOT LEAVING THIS SERVER").contains("R2_ACCESS_KEY_ID")
          .contains("R2_SECRET_ACCESS_KEY").contains("R2_BACKUP_BUCKET").contains("CF_ACCOUNT_ID");
      assertThat(svc.warning()).contains("aren't leaving this server");
      assertThat(svc.status().lastAt()).isNotNull();
      assertThat(svc.status().lastOffsiteAt()).isNull();
    }
  }

  @Test
  void aFailedUploadKeepsTheSnapshotAndSaysSo() throws Exception {
    try (var db = TestDatabase.leased(dir)) {
      var r2 = r2();
      s3.failUploads = true;
      var svc = service(dir.resolve("radiance.db"), r2, db.clock);
      var r = svc.run();
      assertThat(r.outcome()).isEqualTo(BackupService.Outcome.OFFSITE_FAILED);
      assertThat(svc.localBackups()).hasSize(1);
      assertThat(svc.warning()).contains("upload failed");
      s3.failUploads = false;
      db.clock.advance(java.time.Duration.ofMinutes(1));
      assertThat(svc.run().outcome()).isEqualTo(BackupService.Outcome.DONE);
      assertThat(svc.warning()).as("it recovers").isNull();
    }
  }

  @Test
  void aWrongSecretIsAFailureNotASilentSuccess() throws Exception {
    try (var db = TestDatabase.leased(dir)) {
      r2();
      var wrong = R2Settings.from(Map.of("R2_ENDPOINT", s3.url(), "R2_ACCESS_KEY_ID", KEY, "R2_SECRET_ACCESS_KEY", "not-the-secret",
          "R2_BACKUP_BUCKET", "radiance-backups"));
      var r = service(dir.resolve("radiance.db"), wrong, db.clock).run();
      assertThat(r.outcome()).isEqualTo(BackupService.Outcome.OFFSITE_FAILED);
      assertThat(s3.objects).isEmpty();
    }
  }

  @Test
  void r2PruningAndLocalPruningFollowRetention() throws Exception {
    try (var db = TestDatabase.leased(dir)) {
      var svc = service(dir.resolve("radiance.db"), r2(), db.clock);
      for (int d = 0; d < 20; d++) {
        assertThat(svc.run().outcome()).isEqualTo(BackupService.Outcome.DONE);
        db.clock.advance(java.time.Duration.ofDays(1));
      }
      assertThat(svc.localBackups()).as("three on the server").hasSize(3);
      var kept = s3.objects.keySet().stream().filter(k -> k.contains("/backups/")).toList();
      assertThat(kept.size()).as("14 dailies and the weeklies before them").isBetween(14, 17);
    }
  }

  @Test
  void theSchedulerRunsWhenDueOverdueOrNewlyKeyed() throws Exception {
    try (var db = TestDatabase.leased(dir)) {
      Path file = dir.resolve("radiance.db");
      var clock = db.clock;
      clock.set(Instant.parse("2026-11-02T09:00:00Z").toEpochMilli());
      var local = service(file, NO_KEYS, clock);
      var sched = new BackupScheduler(local, "30 8 * * *", clock, true);
      assertThat(sched.lastFire(clock.nowMs())).isEqualTo(Instant.parse("2026-11-02T08:30:00Z").toEpochMilli());
      assertThat(sched.due(clock.nowMs())).as("never backed up").isTrue();
      assertThat(sched.tick()).isTrue();
      assertThat(sched.due(clock.nowMs())).as("done for tonight, even if only locally").isFalse();
      clock.advance(java.time.Duration.ofDays(1));
      assertThat(sched.due(clock.nowMs())).as("the next night").isTrue();
      sched.tick();

      // R adds the keys and restarts: the upload doesn't wait for tomorrow night
      var keyed = new BackupScheduler(service(file, r2(), clock), "30 8 * * *", clock, true);
      assertThat(keyed.due(clock.nowMs())).isTrue();
      assertThat(keyed.tick()).isTrue();
      assertThat(s3.objects).isNotEmpty();
      assertThat(keyed.due(clock.nowMs())).isFalse();

      // a failure retries after an hour, not every minute
      s3.failUploads = true;
      clock.advance(java.time.Duration.ofDays(1));
      assertThat(keyed.tick()).isTrue();
      clock.advance(java.time.Duration.ofMinutes(10));
      assertThat(keyed.due(clock.nowMs())).isFalse();
      clock.advance(java.time.Duration.ofHours(1));
      assertThat(keyed.due(clock.nowMs())).isTrue();
    }
  }

  // --------------------------------------------------------------------------------------- restoring

  final ByteArrayOutputStream console = new ByteArrayOutputStream();

  int restore(Path db, R2Settings r2, String... args) {
    return new Restore(db, db.toAbsolutePath().getParent().resolve("backups"), r2, new PrintStream(console, true, StandardCharsets.UTF_8))
        .run(List.of(args));
  }

  static long count(Path db, String sql) throws Exception {
    try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath()); var st = c.createStatement();
        var r = st.executeQuery(sql)) {
      r.next();
      return r.getLong(1);
    }
  }

  /** The drill: back up, lose the database, restore from R2, and the app opens it with the data there. */
  @Test
  void aLostDatabaseComesBackFromR2AndTheAppOpensIt() throws Exception {
    Path file = dir.resolve("radiance.db");
    var clock = new ManualClock(TestDatabase.START);
    try (var db = TestDatabase.sqlite(file, "first", clock)) {
      db.lease.tryAcquire();
      db.commit(new UnitOfWork().add("INSERT INTO session_log (id, started_at) VALUES ('precious', 1)"));
      // taken while the app holds its lease, as every nightly backup is
      assertThat(service(file, r2(), clock).run().outcome()).isEqualTo(BackupService.Outcome.DONE);
      db.lease.release();
    }
    for (String f : List.of("radiance.db", "radiance.db-wal", "radiance.db-shm")) Files.deleteIfExists(dir.resolve(f));
    // the disk is gone too: nothing local to fall back on
    try (var files = Files.walk(dir.resolve("backups"))) {
      files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }

    assertThat(restore(file, r2(), "latest")).as(console.toString()).isEqualTo(Restore.OK);

    try (var db = TestDatabase.sqlite(file, "second", clock)) {
      assertThat(db.count("session_log")).isEqualTo(1);
      // regression: the snapshot's lease used to lock the restored app out of writing until it expired
      assertThat(db.lease.tryAcquire()).as("no stale lease in the way").isTrue();
    }
  }

  @Test
  void restoreRefusesWhileTheAppHoldsTheLease() throws Exception {
    Path file = dir.resolve("radiance.db");
    long now = System.currentTimeMillis();
    try (var db = TestDatabase.sqlite(file, "live", new ManualClock(now))) {
      assertThat(db.lease.tryAcquire()).isTrue();
      assertThat(service(file, NO_KEYS, db.clock).run().outcome()).isEqualTo(BackupService.Outcome.LOCAL_ONLY);
      long before = Files.size(file);
      assertThat(restore(file, NO_KEYS, "latest")).isEqualTo(Restore.APP_RUNNING);
      assertThat(console.toString()).contains("docker compose stop radiance");
      assertThat(Files.size(file)).as("nothing touched").isEqualTo(before);
    }
  }

  @Test
  void aBadBackupLeavesTheCurrentDatabaseAlone() throws Exception {
    Path file = dir.resolve("radiance.db");
    var clock = new ManualClock(TestDatabase.START);
    try (var db = TestDatabase.sqlite(file, "first", clock)) {
      db.lease.tryAcquire();
      db.commit(new UnitOfWork().add("INSERT INTO session_log (id, started_at) VALUES ('current', 1)"));
    }
    Path notGzip = dir.resolve("radiance-20261102T083000Z.db.gz");
    Files.writeString(notGzip, "this is not a database");
    assertThat(restore(file, NO_KEYS, "--file", notGzip.toString())).isEqualTo(Restore.FAILED);
    assertThat(console.toString()).contains("nothing was changed");

    Path notDb = dir.resolve("radiance-20261102T083100Z.db.gz");
    try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(notDb))) {
      out.write("not sqlite at all, but gzipped".getBytes(StandardCharsets.UTF_8));
    }
    assertThat(restore(file, NO_KEYS, "--file", notDb.toString())).isEqualTo(Restore.FAILED);

    assertThat(count(file, "SELECT COUNT(*) FROM session_log WHERE id = 'current'")).isEqualTo(1);
    assertThat(dir.resolve("radiance.db.restoring")).doesNotExist();
  }

  @Test
  void restoreKeepsThePreviousDatabaseAside() throws Exception {
    Path file = dir.resolve("radiance.db");
    var clock = new ManualClock(TestDatabase.START);
    try (var db = TestDatabase.sqlite(file, "first", clock)) {
      db.lease.tryAcquire();
      db.commit(new UnitOfWork().add("INSERT INTO session_log (id, started_at) VALUES ('old', 1)"));
      assertThat(service(file, NO_KEYS, clock).run().outcome()).isEqualTo(BackupService.Outcome.LOCAL_ONLY);
      db.commit(new UnitOfWork().add("INSERT INTO session_log (id, started_at) VALUES ('newer', 2)"));
    }
    assertThat(restore(file, NO_KEYS, "latest")).as(console.toString()).isEqualTo(Restore.OK);
    assertThat(count(file, "SELECT COUNT(*) FROM session_log WHERE id = 'newer'")).as("back to the snapshot").isZero();
    try (var dirs = Files.list(dir)) {
      Path aside = dirs.filter(p -> p.getFileName().toString().startsWith("pre-restore-")).findFirst().orElseThrow();
      assertThat(count(aside.resolve("radiance.db"), "SELECT COUNT(*) FROM session_log WHERE id = 'newer'")).isEqualTo(1);
    }
  }

  @Test
  void restoreExplainsItselfWhenThereIsNothingToDo() {
    Path file = dir.resolve("radiance.db");
    assertThat(restore(file, NO_KEYS)).isEqualTo(Restore.USAGE);
    assertThat(restore(file, NO_KEYS, "latest")).isEqualTo(Restore.FAILED);
    assertThat(console.toString()).contains("No backups found");
    assertThat(restore(file, NO_KEYS, "radiance-20260101T000000Z.db.gz")).isEqualTo(Restore.FAILED);
    assertThat(console.toString()).contains("R2 isn't configured");
    assertThat(restore(file, NO_KEYS, "--list")).isEqualTo(Restore.OK);
  }

  @Test
  void settingsNameExactlyWhatsMissing() {
    assertThat(NO_KEYS.missing()).containsExactly("R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BACKUP_BUCKET", "CF_ACCOUNT_ID");
    assertThat(NO_KEYS.client()).isEmpty();
    var ok = R2Settings.from(Map.of("CF_ACCOUNT_ID", "abc", "R2_ACCESS_KEY_ID", "k", "R2_SECRET_ACCESS_KEY", "s", "R2_BUCKET", "media"));
    assertThat(ok.missing()).isEmpty();
    assertThat(ok.bucket()).as("falls back to the media bucket").isEqualTo("media");
    assertThat(ok.region()).isEqualTo("auto");
  }

  // ------------------------------------------------------------------------------------- the stand-in

  /** A tiny S3 on a real socket: stores objects in memory and rejects anything not properly signed. */
  static final class FakeS3 implements AutoCloseable {
    final Map<String, byte[]> objects = new ConcurrentSkipListMap<>();
    volatile boolean failUploads;
    private final HttpServer server;

    FakeS3() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange x) throws IOException {
      try (x) {
        byte[] body = x.getRequestBody().readAllBytes();
        String path = x.getRequestURI().getRawPath();
        String query = x.getRequestURI().getRawQuery();
        if (!signedProperly(x, path, query == null ? "" : query, body)) {
          reply(x, 403, "<Error><Code>SignatureDoesNotMatch</Code></Error>");
          return;
        }
        String key = path.substring(1);
        switch (x.getRequestMethod()) {
          case "PUT" -> {
            if (failUploads) {
              reply(x, 500, "<Error><Code>InternalError</Code></Error>");
              return;
            }
            objects.put(key, body);
            reply(x, 200, "");
          }
          case "HEAD" -> {
            var o = objects.get(key);
            if (o == null) {
              x.sendResponseHeaders(404, -1);
            } else {
              x.getResponseHeaders().set("Content-Length", String.valueOf(o.length));
              x.sendResponseHeaders(200, -1);
            }
          }
          case "DELETE" -> {
            objects.remove(key);
            x.sendResponseHeaders(204, -1);
          }
          case "GET" -> {
            if (query != null && query.contains("list-type=2")) {
              reply(x, 200, listing(key, query));
            } else if (objects.containsKey(key)) {
              x.sendResponseHeaders(200, objects.get(key).length);
              x.getResponseBody().write(objects.get(key));
            } else {
              reply(x, 404, "<Error><Code>NoSuchKey</Code></Error>");
            }
          }
          default -> reply(x, 405, "");
        }
      }
    }

    /** One page of two, so pagination gets exercised. */
    private String listing(String bucketPath, String query) {
      String bucket = bucketPath.replaceAll("/$", "");
      var keys = new ArrayList<>(objects.keySet().stream().filter(k -> k.startsWith(bucket + "/")).toList());
      int from = 0;
      var token = java.util.regex.Pattern.compile("continuation-token=(\\d+)").matcher(query);
      if (token.find()) from = Integer.parseInt(token.group(1));
      int to = Math.min(keys.size(), from + 2);
      var xml = new StringBuilder("<ListBucketResult>");
      for (String k : keys.subList(from, to)) {
        xml.append("<Contents><Key>").append(k.substring(bucket.length() + 1)).append("</Key><Size>").append(objects.get(k).length)
            .append("</Size></Contents>");
      }
      if (to < keys.size()) xml.append("<NextContinuationToken>").append(to).append("</NextContinuationToken>");
      return xml.append("</ListBucketResult>").toString();
    }

    private boolean signedProperly(HttpExchange x, String path, String query, byte[] body) {
      String auth = x.getRequestHeaders().getFirst("Authorization");
      if (auth == null) return false;
      var headers = new TreeMap<String, String>();
      headers.put("host", x.getRequestHeaders().getFirst("Host"));
      headers.put("x-amz-content-sha256", x.getRequestHeaders().getFirst("x-amz-content-sha256"));
      headers.put("x-amz-date", x.getRequestHeaders().getFirst("x-amz-date"));
      if (!x.getRequestMethod().equals("PUT")) {
        // a body-less request must claim the empty hash; a PUT's is checked below
        if (body.length > 0) return false;
      } else {
        try {
          String real = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
          if (!real.equals(headers.get("x-amz-content-sha256"))) return false;
        } catch (java.security.NoSuchAlgorithmException e) {
          return false;
        }
      }
      return auth.equals(S3Client.authorization(x.getRequestMethod(), path, query, headers, headers.get("x-amz-content-sha256"),
          headers.get("x-amz-date"), "auto", KEY, SECRET));
    }

    private static void reply(HttpExchange x, int status, String text) throws IOException {
      byte[] b = text.getBytes(StandardCharsets.UTF_8);
      x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
      if (b.length > 0) x.getResponseBody().write(b);
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
