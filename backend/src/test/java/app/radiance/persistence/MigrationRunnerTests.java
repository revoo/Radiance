package app.radiance.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.radiance.kernel.ManualClock;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationRunnerTests {

  @TempDir Path dir;
  WriterLane lane;
  JdbcSql sql;
  final ManualClock clock = new ManualClock(TestDatabase.START);

  @BeforeEach
  void open() {
    lane = new WriterLane("lane");
    sql = JdbcSql.open(dir.resolve("m.db"), 1, lane, new LeaseHolder());
  }

  @AfterEach
  void close() {
    lane.close();
    sql.close();
  }

  MigrationRunner runner(MigrationRunner.Migration... ms) {
    return new MigrationRunner(sql, sql, clock, List.of(ms));
  }

  Set<String> tables() {
    return sql.query("SELECT name FROM sqlite_master WHERE type = 'table'", List.of(),
            r -> r.str("name")).stream().collect(Collectors.toSet());
  }

  @Test
  void appliesInVersionOrderOnce() {
    var second = new MigrationRunner.Migration("0002", "more", "CREATE TABLE b (id INTEGER);");
    var first = new MigrationRunner.Migration("0001", "init", "CREATE TABLE a (id INTEGER);");
    assertThat(runner(second, first).migrate()).containsExactly("0001", "0002");
    assertThat(runner(second, first).migrate()).isEmpty();
    assertThat(tables()).contains("a", "b", "schema_migrations");
  }

  @Test
  void aFailingMigrationLeavesNothingBehind() {
    var bad = new MigrationRunner.Migration("0001", "bad",
        "CREATE TABLE a (id INTEGER); INSERT INTO nowhere VALUES (1);");
    assertThatThrownBy(() -> runner(bad).migrate()).isNotNull();
    assertThat(tables()).doesNotContain("a");
    assertThat(sql.query("SELECT version FROM schema_migrations", List.of(), r -> r.str("version")))
        .isEmpty();
  }

  @Test
  void editingAnAppliedMigrationIsAnError() {
    runner(new MigrationRunner.Migration("0001", "init", "CREATE TABLE a (id INTEGER);")).migrate();
    var edited = new MigrationRunner.Migration("0001", "init", "CREATE TABLE a (id TEXT);");
    assertThatThrownBy(() -> runner(edited).migrate()).hasMessageContaining("changed after");
  }

  @Test
  void lineEndingsDoNotChangeTheChecksum() {
    var lf = new MigrationRunner.Migration("0001", "x", "CREATE TABLE a (id INTEGER);\n");
    var crlf = new MigrationRunner.Migration("0001", "x", "CREATE TABLE a (id INTEGER);\r\n");
    assertThat(lf.checksum()).isEqualTo(crlf.checksum());
  }
}
