package app.radiance.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * The {@code restore} run mode (ADR 0019): {@code restore latest}, {@code restore <backup name>},
 * {@code restore --file <path>} or {@code restore --list}. Runs before Spring starts, so nothing has the
 * database open. It refuses while a process holds a live writer lease, verifies what it downloaded before
 * touching anything, and moves the current database aside rather than deleting it.
 */
public final class Restore {

  /** Exit codes. */
  static final int OK = 0, FAILED = 1, USAGE = 2, APP_RUNNING = 3;

  private final Path db;
  private final Path backups;
  private final R2Settings r2;
  private final PrintStream out;

  Restore(Path db, Path backups, R2Settings r2, PrintStream out) {
    this.db = db;
    this.backups = backups;
    this.r2 = r2;
    this.out = out;
  }

  public static boolean isRestoreMode(String[] args) {
    return args.length > 0 && "restore".equals(args[0]);
  }

  /** The run mode: paths from the same environment the app uses. */
  public static int main(String[] args, Map<String, String> env, PrintStream out) {
    String dbPath = env.getOrDefault("RADIANCE_PERSISTENCE_DB_PATH", "data/radiance.db");
    Path db = Path.of(dbPath);
    Path parent = db.toAbsolutePath().getParent();
    return new Restore(db, parent.resolve("backups"), R2Settings.from(env), out).run(Arrays.asList(args).subList(1, args.length));
  }

  int run(List<String> args) {
    try {
      if (args.isEmpty()) return usage();
      if (args.contains("--list")) return list();
      boolean force = args.contains("--force");
      var rest = new ArrayList<>(args);
      rest.remove("--force");
      if (rest.isEmpty()) return usage();
      Path file;
      Path download = null;
      if (rest.getFirst().equals("--file")) {
        if (rest.size() < 2) return usage();
        file = Path.of(rest.get(1));
      } else {
        String name = rest.getFirst().equals("latest") ? latest() : rest.getFirst();
        if (name == null) {
          out.println("No backups found, on R2 or in " + backups + ".");
          return FAILED;
        }
        Path local = backups.resolve(baseName(name));
        if (Files.exists(local)) {
          file = local;
        } else {
          var client = r2.client().orElse(null);
          if (client == null) {
            out.println(name + " isn't on this server, and R2 isn't configured (missing " + String.join(", ", r2.missing()) + ").");
            return FAILED;
          }
          Files.createDirectories(db.toAbsolutePath().getParent());
          download = db.resolveSibling(baseName(name) + ".download");
          String key = name.contains("/") ? name : BackupService.REMOTE_PREFIX + name;
          out.println("Downloading " + key + " ...");
          client.get(key, download);
          file = download;
        }
      }
      try {
        return install(file, force);
      } finally {
        if (download != null) Files.deleteIfExists(download);
      }
    } catch (IOException | SQLException e) {
      out.println("Restore failed, nothing was changed: " + e.getMessage());
      return FAILED;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return FAILED;
    }
  }

  private int install(Path gz, boolean force) throws IOException, SQLException {
    String holder = liveLease();
    if (holder != null && !force) {
      out.println("Radiance looks like it's running (it holds the writer lease as " + holder + ").");
      out.println("Stop it first: docker compose stop radiance. A crashed app's lease expires within 30 seconds.");
      return APP_RUNNING;
    }
    Files.createDirectories(db.toAbsolutePath().getParent());
    Path incoming = db.resolveSibling(db.getFileName() + ".restoring");
    Files.deleteIfExists(incoming);
    try {
      try (InputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
        Files.copy(in, incoming);
      }
      BackupService.verify(incoming);
      clearLease(incoming);
      Path aside = db.resolveSibling("pre-restore-" + Instant.now().toEpochMilli());
      boolean moved = false;
      for (String suffix : List.of("", "-wal", "-shm")) {
        Path current = db.resolveSibling(db.getFileName() + suffix);
        if (Files.exists(current)) {
          Files.createDirectories(aside);
          Files.move(current, aside.resolve(current.getFileName()));
          moved = true;
        }
      }
      Files.move(incoming, db, StandardCopyOption.ATOMIC_MOVE);
      out.println("Restored " + gz.getFileName() + " to " + db + ".");
      out.println(moved ? "The previous database is kept in " + aside + "." : "There was no previous database.");
      out.println("Start the app; it applies any newer migrations by itself.");
      return OK;
    } finally {
      Files.deleteIfExists(incoming);
    }
  }

  /**
   * The snapshot remembers the writer lease of the process it was taken from, long gone by now. No app is
   * running during a restore, so this one write doesn't go through the writer lane.
   */
  private static void clearLease(Path file) throws SQLException {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()); var st = c.createStatement()) {
      st.executeUpdate("UPDATE world SET lease_owner = NULL, lease_until = NULL");
    }
  }

  /** The lease owner if a process holds a live writer lease on the current database, else null. */
  private String liveLease() {
    if (!Files.exists(db)) return null;
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath()); var st = c.createStatement();
        var r = st.executeQuery("SELECT lease_owner, lease_until FROM world")) {
      if (r.next() && r.getString(1) != null && r.getLong(2) > System.currentTimeMillis()) return r.getString(1);
    } catch (SQLException e) {
      out.println("The current database can't be read (" + e.getMessage() + "); continuing.");
    }
    return null;
  }

  private String latest() throws IOException, InterruptedException {
    var names = new ArrayList<String>(localNames());
    var client = r2.client();
    if (client.isPresent()) {
      client.get().list(BackupService.REMOTE_PREFIX).forEach(e -> names.add(e.key()));
    } else {
      out.println("R2 isn't configured (missing " + String.join(", ", r2.missing()) + "): only this server's backups count.");
    }
    return Retention.newest(names).orElse(null);
  }

  private int list() throws IOException, InterruptedException {
    out.println("On this server (" + backups + "):");
    localNames().stream().sorted().forEach(n -> out.println("  " + n));
    var client = r2.client();
    if (client.isEmpty()) {
      out.println("R2 isn't configured (missing " + String.join(", ", r2.missing()) + ").");
    } else {
      out.println("On R2:");
      client.get().list(BackupService.REMOTE_PREFIX).stream().sorted(java.util.Comparator.comparing(S3Client.Entry::key))
          .forEach(e -> out.println("  " + e.key() + "  (" + e.size() / 1024 + " KB)"));
    }
    return OK;
  }

  private List<String> localNames() throws IOException {
    if (!Files.isDirectory(backups)) return List.of();
    try (Stream<Path> files = Files.list(backups)) {
      return files.map(p -> p.getFileName().toString()).filter(n -> Retention.takenAt(n).isPresent()).toList();
    }
  }

  private static String baseName(String name) {
    return name.substring(name.lastIndexOf('/') + 1);
  }

  private int usage() {
    out.println("Usage: restore latest | restore <backup name> | restore --file <path.db.gz> | restore --list  [--force]");
    return USAGE;
  }
}
