package app.radiance.backup;

import app.radiance.kernel.Json;
import app.radiance.kernel.WallClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One backup (ADR 0019): a consistent {@code VACUUM INTO} snapshot of the live database, checked and
 * gzipped into {@code data/backups}, then uploaded to R2 and pruned. Without R2 keys it still takes the
 * local snapshot and says so as loudly as it can: a backup on the same disk isn't one.
 */
public final class BackupService {

  public enum Outcome {
    /** The snapshot is on R2. */
    DONE,
    /** The snapshot is only on this server: no R2 keys. */
    LOCAL_ONLY,
    /** The snapshot is only on this server: the upload failed. */
    OFFSITE_FAILED,
    /** No snapshot at all. */
    FAILED
  }

  public record Result(Outcome outcome, String file, long bytes, String offsiteKey, String error) {}

  /** Kept beside the backups, not in the database being backed up. Times are wall-clock ms. */
  public record Status(Long lastAttemptAt, Long lastAt, String lastFile, Long lastBytes, Long lastOffsiteAt, String lastOffsiteKey,
      String lastError) {
    static final Status NONE = new Status(null, null, null, null, null, null, null);
  }

  public static final String REMOTE_PREFIX = "backups/";
  private static final Logger log = LoggerFactory.getLogger(BackupService.class);

  private final Path db;
  private final Path dir;
  private final R2Settings r2;
  private final int keepDaily, keepWeekly, keepLocal;
  private final WallClock clock;
  private final AtomicBoolean running = new AtomicBoolean();

  public BackupService(Path db, Path dir, R2Settings r2, int keepDaily, int keepWeekly, int keepLocal, WallClock clock) {
    this.db = db;
    this.dir = dir;
    this.r2 = r2;
    this.keepDaily = keepDaily;
    this.keepWeekly = keepWeekly;
    this.keepLocal = keepLocal;
    this.clock = clock;
  }

  public R2Settings r2() {
    return r2;
  }

  public boolean offsiteConfigured() {
    return r2.missing().isEmpty();
  }

  /** The banner for logs when backups can't leave the server, or null when they can. */
  public String missingKeysBanner() {
    if (offsiteConfigured()) return null;
    String rule = "!".repeat(96);
    return "\n" + rule + "\n!!! BACKUPS ARE NOT LEAVING THIS SERVER: missing " + String.join(", ", r2.missing()) + "\n"
        + "!!! Snapshots stay in " + dir + " only. If this disk is lost, the whole world is lost.\n"
        + "!!! Add the keys to .env and restart. See docs/ops/hosting.md.\n" + rule;
  }

  public Result run() {
    if (!running.compareAndSet(false, true)) return new Result(Outcome.FAILED, null, 0, null, "a backup is already running");
    long now = clock.nowMs();
    var status = status();
    try {
      Files.createDirectories(dir);
      String name = Retention.fileName(Instant.ofEpochMilli(now));
      Path file = dir.resolve(name);
      snapshot(file);
      long bytes = Files.size(file);
      pruneLocal();
      log.info("Backup snapshot {} ({} KB)", name, bytes / 1024);
      var client = r2.client();
      if (client.isEmpty()) {
        log.error("{}", missingKeysBanner());
        write(new Status(now, now, name, bytes, status.lastOffsiteAt(), status.lastOffsiteKey(), null));
        return new Result(Outcome.LOCAL_ONLY, name, bytes, null, null);
      }
      try {
        String key = REMOTE_PREFIX + name;
        client.get().put(key, file);
        // an upload we can't see back isn't a backup
        long stored = client.get().head(key).orElse(-1L);
        if (stored != bytes) throw new IOException("uploaded " + bytes + " bytes but the store has " + stored);
        pruneRemote();
        log.info("Backup uploaded to {}", key);
        write(new Status(now, now, name, bytes, now, key, null));
        return new Result(Outcome.DONE, name, bytes, key, null);
      } catch (IOException e) {
        log.error("BACKUP UPLOAD FAILED; the snapshot is only on this server: {}", e.getMessage());
        write(new Status(now, now, name, bytes, status.lastOffsiteAt(), status.lastOffsiteKey(), "upload failed: " + e.getMessage()));
        return new Result(Outcome.OFFSITE_FAILED, name, bytes, null, e.getMessage());
      }
    } catch (IOException | SQLException e) {
      log.error("BACKUP FAILED: {}", e.toString());
      write(new Status(now, status.lastAt(), status.lastFile(), status.lastBytes(), status.lastOffsiteAt(), status.lastOffsiteKey(),
          "backup failed: " + e.getMessage()));
      return new Result(Outcome.FAILED, null, 0, null, e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(Outcome.FAILED, null, 0, null, "interrupted");
    } finally {
      running.set(false);
    }
  }

  public Status status() {
    Path file = dir.resolve("status.json");
    try {
      return Files.exists(file) ? Json.MAPPER.readValue(Files.readString(file), Status.class) : Status.NONE;
    } catch (IOException | RuntimeException e) {
      log.warn("Unreadable backup status: {}", e.toString());
      return Status.NONE;
    }
  }

  /**
   * What R should know, or null if all's well: no R2 keys, a failed last run, or a backup that's gone
   * stale. The SPA shows it as a banner (ADR 0019).
   */
  public String warning() {
    var s = status();
    if (!offsiteConfigured()) {
      return "Backups aren't leaving this server: " + String.join(", ", r2.missing()) + " missing. If this disk is lost, the world is lost.";
    }
    if (s.lastError() != null) return "The last backup had a problem: " + s.lastError();
    Long off = s.lastOffsiteAt();
    if (off != null && clock.nowMs() - off > Duration.ofHours(48).toMillis()) {
      return "No backup has reached R2 for " + Duration.ofMillis(clock.nowMs() - off).toHours() + " hours.";
    }
    return null;
  }

  /** {@code VACUUM INTO}: consistent even with the app writing; checked before it counts. */
  private void snapshot(Path gz) throws IOException, SQLException {
    if (!Files.exists(db)) throw new IOException("no database at " + db);
    Path raw = gz.resolveSibling(gz.getFileName() + ".tmp");
    Files.deleteIfExists(raw);
    try {
      try (var c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath()); var st = c.prepareStatement("VACUUM INTO ?")) {
        st.setString(1, raw.toAbsolutePath().toString());
        st.execute();
      }
      verify(raw);
      Path partial = gz.resolveSibling(gz.getFileName() + ".part");
      try (InputStream in = Files.newInputStream(raw); OutputStream out = new GZIPOutputStream(Files.newOutputStream(partial))) {
        in.transferTo(out);
      }
      Files.move(partial, gz, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(raw);
    }
  }

  /** Throws unless {@code file} is a healthy SQLite database with a world in it. */
  public static void verify(Path file) throws SQLException, IOException {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()); var st = c.createStatement()) {
      try (var r = st.executeQuery("PRAGMA integrity_check")) {
        if (!r.next() || !"ok".equalsIgnoreCase(r.getString(1))) {
          throw new IOException("integrity check failed: " + (r.isClosed() ? "no result" : r.getString(1)));
        }
      }
      try (var r = st.executeQuery("SELECT COUNT(*) FROM world")) {
        if (!r.next() || r.getLong(1) != 1) throw new IOException("no world row");
      }
    }
  }

  private void pruneLocal() throws IOException {
    for (Path p : localBackups().stream().skip(keepLocal).toList()) Files.deleteIfExists(p);
  }

  /** Local backups, newest first. */
  public List<Path> localBackups() throws IOException {
    if (!Files.isDirectory(dir)) return List.of();
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(p -> Retention.takenAt(p.getFileName().toString()).isPresent())
          .sorted(Comparator.comparing((Path p) -> Retention.takenAt(p.getFileName().toString()).orElseThrow()).reversed())
          .toList();
    }
  }

  private void pruneRemote() throws IOException, InterruptedException {
    var client = r2.client().orElseThrow();
    var keys = new ArrayList<String>();
    client.list(REMOTE_PREFIX).forEach(e -> keys.add(e.key()));
    for (String key : Retention.toDelete(keys, keepDaily, keepWeekly)) client.delete(key);
  }

  private void write(Status s) {
    try {
      Files.createDirectories(dir);
      Path tmp = dir.resolve("status.json.tmp");
      Files.writeString(tmp, Json.write(s));
      Files.move(tmp, dir.resolve("status.json"), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      log.warn("Couldn't write the backup status: {}", e.toString());
    }
  }
}
