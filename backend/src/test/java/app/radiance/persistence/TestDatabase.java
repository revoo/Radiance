package app.radiance.persistence;

import app.radiance.kernel.ManualClock;
import java.nio.file.Path;
import java.time.Duration;

/**
 * A migrated database with a world row, a writer lane and a lease, as one process would have it.
 * Fields are typed as the ports so the contract suite can run against another adapter (D1, M9).
 */
public final class TestDatabase implements AutoCloseable {

  public static final Duration LEASE_TTL = Duration.ofSeconds(30);
  public static final long START = 1_790_000_000_000L;

  public final Sql sql;
  public final SqlAdmin admin;
  public final WriterLane lane;
  public final LeaseHolder holder;
  public final WriterLease lease;
  public final ManualClock clock;
  private final Runnable resource;

  TestDatabase(
      Sql sql, SqlAdmin admin, WriterLane lane, LeaseHolder holder, ManualClock clock,
      String owner, Runnable resource) {
    this.sql = sql;
    this.admin = admin;
    this.lane = lane;
    this.holder = holder;
    this.clock = clock;
    this.resource = resource;
    this.lease = new WriterLease(sql, admin, holder, clock, owner, LEASE_TTL);
  }

  /** Opens (and if needed migrates) the SQLite file as a process named {@code owner}. */
  public static TestDatabase sqlite(Path file, String owner, ManualClock clock) {
    var lane = new WriterLane("writer-lane-" + owner);
    var holder = new LeaseHolder();
    var sql = JdbcSql.open(file, 2, lane, holder);
    MigrationRunner.fromClasspath(sql, sql, clock).migrate();
    sql.execute(worldRow(clock.nowMs()));
    return new TestDatabase(sql, sql, lane, holder, clock, owner, () -> {
      lane.close();
      sql.close();
    });
  }

  /** A fresh database whose process already holds the lease. */
  public static TestDatabase leased(Path dir) {
    var db = sqlite(dir.resolve("radiance.db"), "test", new ManualClock(START));
    if (!db.lease.tryAcquire()) throw new IllegalStateException("could not take the lease");
    return db;
  }

  static UnitOfWork worldRow(long now) {
    return new UnitOfWork()
        .add(
            "INSERT INTO world (id, name, network_name, timezone, seed, committed_until,"
                + " scripted_until, settings_json, created_at) VALUES (?, 'Test', 'Radiance',"
                + " 'America/Chicago', 7, ?, ?, '{}', ?) ON CONFLICT (id) DO NOTHING",
            Schema.WORLD_ID, now, now, now);
  }

  /** Commits on the writer lane, as domain code will. */
  public void commit(UnitOfWork uow) {
    lane.run(() -> sql.commit(uow));
  }

  public long count(String table) {
    return sql.queryOne("SELECT COUNT(*) AS n FROM " + table, java.util.List.of(), r -> r.lng("n"))
        .orElseThrow();
  }

  @Override
  public void close() {
    resource.run();
  }
}
