package app.radiance.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.radiance.kernel.ManualClock;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The persistence contract (SPEC §10): every adapter of {@link Sql} must pass it. M9 adds a D1
 * subclass that opens {@link TestDatabase}s through the edge Worker.
 */
abstract class SqlContractTests {

  @TempDir Path dir;
  private final List<TestDatabase> opened = new ArrayList<>();

  /** Opens the shared database file as a process named {@code owner}. */
  abstract TestDatabase open(Path file, String owner, ManualClock clock);

  TestDatabase process(String owner, ManualClock clock) {
    var db = open(dir.resolve("radiance.db"), owner, clock);
    opened.add(db);
    return db;
  }

  TestDatabase leased() {
    var db = process("solo", new ManualClock(TestDatabase.START));
    assertThat(db.lease.tryAcquire()).isTrue();
    return db;
  }

  @AfterEach
  void closeAll() {
    for (var db : opened.reversed()) db.close();
  }

  static List<Object> actor(int i) {
    return List.of("a" + i, "BOT", "handle." + i, "Name " + i, "[]", TestDatabase.START);
  }

  static final List<String> ACTOR_COLS =
      List.of("id", "kind", "handle", "display_name", "circles_json", "created_at");

  @Test
  void migrationsAreIdempotent() {
    var db = leased();
    var runner = MigrationRunner.fromClasspath(db.sql, db.admin, db.clock);
    assertThat(runner.migrate()).isEmpty();
    assertThat(db.count("schema_migrations")).isEqualTo(2);
  }

  @Test
  void valuesRoundTripThroughEveryAccessor() {
    var db = leased();
    db.admin.execute(
        new UnitOfWork()
            .add("CREATE TABLE scratch (id TEXT PRIMARY KEY, s TEXT, n INTEGER, d REAL, b INTEGER,"
                + " j TEXT, e TEXT)"));
    db.commit(
        new UnitOfWork()
            .add("INSERT INTO scratch VALUES (?, ?, ?, ?, ?, ?, ?)",
                "x", "héllo 👀", 1L << 40, 0.25, true, "{\"k\":[1,2]}", Thread.State.NEW)
            .add("INSERT INTO scratch (id) VALUES (?)", "nulls"));

    record Out(String s, long n, double d, boolean b, int jk, Thread.State e) {}
    var row = db.sql.queryOne("SELECT * FROM scratch WHERE id = ?", List.of("x"),
        r -> new Out(r.str("s"), r.lng("n"), r.dbl("d"), r.bool("b"),
            r.json("j").get("k").get(1).asInt(), r.enm("e", Thread.State.class)));
    assertThat(row).contains(new Out("héllo 👀", 1L << 40, 0.25, true, 2, Thread.State.NEW));

    var nulls = db.sql.queryOne("SELECT * FROM scratch WHERE id = ?", List.of("nulls"),
            r -> List.of(r.isNull("n"), r.str("s") == null, r.lngOrNull("n") == null,
                r.dblOrNull("d") == null, r.json("j") == null,
                r.enm("e", Thread.State.class) == null))
        .orElseThrow();
    assertThat(nulls).containsOnly(true);
  }

  @Test
  void aThousandInsertUnitCommitsAtomically() {
    var db = leased();
    var rows = new ArrayList<List<Object>>();
    for (int i = 0; i < 1000; i++) rows.add(actor(i));
    var uow = new UnitOfWork().insertRows("actor", ACTOR_COLS, rows);
    assertThat(uow.statements()).allSatisfy(s -> assertThat(s.args()).hasSizeLessThanOrEqualTo(100));

    // The last statement violates a foreign key: nothing before it may survive.
    var failing = new UnitOfWork().insertRows("actor", ACTOR_COLS, rows)
        .add("INSERT INTO persona (actor_id, traits_json, quirks_json, interests_json,"
            + " politics_json, biases_json, taste_json, temperament_json, voice_json, routine_json,"
            + " values_json, fears_json, desires_json, off_network_json, identity_json, backstory)"
            + " VALUES ('nobody', '{}', '[]', '{}', '{}', '[]', '{}', '{}', '{}', '{}', '[]', '[]',"
            + " '[]', '[]', '{}', '')");
    assertThatThrownBy(() -> db.commit(failing)).isNotNull();
    assertThat(db.count("actor")).isZero();

    db.commit(uow);
    assertThat(db.count("actor")).isEqualTo(1000);
  }

  @Test
  void retriedUnitsLeaveTheSameState() {
    var db = leased();
    var uow = new UnitOfWork()
        .upsertRows("actor", ACTOR_COLS, List.of("id"), List.of(actor(1), actor(2)));
    db.commit(uow);
    db.commit(uow);
    assertThat(db.count("actor")).isEqualTo(2);
  }

  @Test
  void aWriteOffTheWriterLaneFailsFast() {
    var db = leased();
    assertThatThrownBy(() -> db.sql.commit(new UnitOfWork().insertRows("actor", ACTOR_COLS,
            List.of(actor(1)))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writer lane");
    assertThat(db.count("actor")).isZero();
  }

  @Test
  void aWriteWithoutTheLeaseFails() {
    var db = process("no-lease", new ManualClock(TestDatabase.START));
    assertThatThrownBy(() -> db.commit(new UnitOfWork().insertRows("actor", ACTOR_COLS,
            List.of(actor(1)))))
        .isInstanceOf(LeaseLostException.class);
  }

  @Test
  void theLeaseIsTakenOverOnlyAfterItExpires() {
    var clock = new ManualClock(TestDatabase.START);
    var app = process("app", clock);
    var batch = process("batch", clock);

    assertThat(app.lease.tryAcquire()).isTrue();
    assertThat(batch.lease.tryAcquire()).isFalse();

    clock.advance(TestDatabase.LEASE_TTL.minusSeconds(1));
    assertThat(app.lease.renew()).isTrue();
    clock.advance(TestDatabase.LEASE_TTL.minusSeconds(1));
    assertThat(batch.lease.tryAcquire()).as("renewed lease still valid").isFalse();

    clock.advance(Duration.ofSeconds(2));
    assertThat(batch.lease.tryAcquire()).isTrue();
    assertThat(batch.holder.current().orElseThrow().epoch())
        .isEqualTo(app.holder.current().orElseThrow().epoch() + 1);
    assertThat(app.lease.renew()).as("the old holder notices").isFalse();
    assertThat(app.holder.isHeld()).isFalse();
  }

  @Test
  void aStaleWritersUnitAbortsAtomically() {
    var clock = new ManualClock(TestDatabase.START);
    var app = process("app", clock);
    var batch = process("batch", clock);
    assertThat(app.lease.tryAcquire()).isTrue();

    clock.advance(TestDatabase.LEASE_TTL.plusSeconds(1));
    assertThat(batch.lease.tryAcquire()).isTrue();

    // app still believes it holds the lease: its unit must abort as a whole.
    assertThat(app.holder.isHeld()).isTrue();
    var stale = new UnitOfWork().insertRows("actor", ACTOR_COLS, List.of(actor(1), actor(2)));
    assertThatThrownBy(() -> app.commit(stale)).isInstanceOf(LeaseLostException.class);
    assertThat(app.count("actor")).isZero();
    assertThat(app.holder.isHeld()).isFalse();

    batch.commit(new UnitOfWork().insertRows("actor", ACTOR_COLS, List.of(actor(3))));
    assertThat(batch.count("actor")).isEqualTo(1);
  }

  @Test
  void releasingTheLeaseLetsAnotherProcessTakeItAtOnce() {
    var clock = new ManualClock(TestDatabase.START);
    var app = process("app", clock);
    var batch = process("batch", clock);
    assertThat(app.lease.tryAcquire()).isTrue();

    app.lease.release();
    assertThat(app.holder.isHeld()).isFalse();
    assertThat(batch.lease.tryAcquire()).isTrue();
  }

  @Test
  void laneTasksSeeTheirOwnFailures() {
    var db = leased();
    var future = db.lane.submit(() -> {
      throw new IllegalArgumentException("boom");
    });
    assertThatThrownBy(future::join).isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThat(db.lane.call(() -> db.lane.isCurrentThread())).isTrue();
  }
}
