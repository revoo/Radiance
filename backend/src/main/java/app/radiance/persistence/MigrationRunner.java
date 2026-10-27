package app.radiance.persistence;

import app.radiance.kernel.WallClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Applies numbered SQL files ({@code 0001_init.sql}, …) through the same port on SQLite and D1
 * (SPEC §4.2). Each migration and its {@code schema_migrations} row commit as one unit, so a failed
 * migration leaves nothing behind. Applied files are checksummed; editing one is an error.
 */
public final class MigrationRunner {

  public record Migration(String version, String name, String script) {

    String checksum() {
      try {
        var normalized = script.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  public static final String DEFAULT_LOCATION = "classpath*:db/migrations/*.sql";

  private final Sql sql;
  private final SqlAdmin admin;
  private final WallClock clock;
  private final List<Migration> migrations;

  public MigrationRunner(Sql sql, SqlAdmin admin, WallClock clock, List<Migration> migrations) {
    this.sql = sql;
    this.admin = admin;
    this.clock = clock;
    this.migrations =
        migrations.stream().sorted(Comparator.comparing(Migration::version)).toList();
  }

  public static MigrationRunner fromClasspath(Sql sql, SqlAdmin admin, WallClock clock) {
    return new MigrationRunner(sql, admin, clock, load(DEFAULT_LOCATION));
  }

  static List<Migration> load(String location) {
    try {
      var out = new ArrayList<Migration>();
      for (var r : new PathMatchingResourcePatternResolver().getResources(location)) {
        String file = r.getFilename();
        if (file == null || !file.matches("\\d{4}_[a-z0-9_]+\\.sql")) {
          throw new IllegalStateException("migration file name must look like 0001_name.sql: " + file);
        }
        out.add(
            new Migration(
                file.substring(0, 4),
                file.substring(5, file.length() - 4),
                r.getContentAsString(StandardCharsets.UTF_8)));
      }
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Applies every pending migration in order and returns the versions it applied. */
  public List<String> migrate() {
    admin.execute(
        new UnitOfWork()
            .add(
                "CREATE TABLE IF NOT EXISTS schema_migrations (version TEXT PRIMARY KEY,"
                    + " name TEXT NOT NULL, checksum TEXT NOT NULL, applied_at INTEGER NOT NULL)"));
    Map<String, String> applied =
        sql
            .query(
                "SELECT version, checksum FROM schema_migrations",
                List.of(),
                r -> Map.entry(r.str("version"), r.str("checksum")))
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    var done = new ArrayList<String>();
    for (var m : migrations) {
      String checksum = m.checksum();
      String existing = applied.get(m.version());
      if (existing != null) {
        if (!existing.equals(checksum)) {
          throw new IllegalStateException(
              "migration " + m.version() + "_" + m.name() + " changed after it was applied;"
                  + " add a new migration instead");
        }
        continue;
      }
      var uow = new UnitOfWork();
      for (String statement : SqlScript.split(m.script())) uow.add(statement);
      uow.add(
          "INSERT INTO schema_migrations (version, name, checksum, applied_at) VALUES (?, ?, ?, ?)",
          m.version(),
          m.name(),
          checksum,
          clock.nowMs());
      admin.execute(uow);
      done.add(m.version());
    }
    return done;
  }
}
