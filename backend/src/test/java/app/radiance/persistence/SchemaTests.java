package app.radiance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shipped migrations follow D1's rules (SPEC §4.4) and build the §4.3 schema. */
class SchemaTests {

  @TempDir Path dir;

  @Test
  void migrationsFollowD1Rules() {
    var migrations = MigrationRunner.load(MigrationRunner.DEFAULT_LOCATION);
    assertThat(migrations).extracting(MigrationRunner.Migration::version).startsWith("0001");
    for (var m : migrations) {
      for (var statement : SqlScript.split(m.script())) {
        var upper = statement.toUpperCase();
        assertThat(upper).doesNotStartWith("BEGIN").doesNotStartWith("COMMIT")
            .doesNotStartWith("PRAGMA");
        assertThat(statement.getBytes(StandardCharsets.UTF_8).length)
            .isLessThan(UnitOfWork.MAX_STATEMENT_BYTES);
      }
    }
  }

  @Test
  void buildsEveryTableWithConsistentForeignKeys() {
    try (var db = TestDatabase.leased(dir)) {
      var tables = db.sql.query("SELECT name FROM sqlite_master WHERE type = 'table'", List.of(),
          r -> r.str("name"));
      assertThat(tables).contains("world", "lease_fence", "session_log", "actor", "persona",
          "actor_state", "relationship", "meter_snapshot", "arc", "arc_participant", "entity",
          "fact", "knowledge", "event", "scheduled_intent", "post", "comment", "reaction",
          "friend_request", "dm_thread", "dm_message", "notification", "memory", "headline",
          "weather_hour", "media", "job", "budget_window", "batch_run", "llm_call");
      var violations = db.sql.query("PRAGMA foreign_key_check", List.of(), r -> r.str("table"));
      assertThat(violations).isEmpty();
    }
  }

  @Test
  void aParkedJobBlocksADuplicate() {
    try (var db = TestDatabase.leased(dir)) {
      String insert = "INSERT INTO job (id, kind, ref_id, priority, status, run_after, created_at)"
          + " VALUES (?, 'RENDER_THREAD', 'post1', 1, ?, 0, 0)";
      db.commit(new UnitOfWork().add(insert, "j1", "WAITING"));
      org.assertj.core.api.Assertions.assertThatThrownBy(
          () -> db.commit(new UnitOfWork().add(insert, "j2", "PENDING")))
          .hasMessageContaining("UNIQUE");
      db.commit(new UnitOfWork().add("UPDATE job SET status = 'DONE' WHERE id = 'j1'"));
      db.commit(new UnitOfWork().add(insert, "j2", "PENDING"));
      assertThat(db.count("job")).isEqualTo(2);
    }
  }
}
