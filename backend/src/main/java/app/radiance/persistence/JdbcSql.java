package app.radiance.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * The SQLite adapter (SPEC §4.2): {@code journal_mode=WAL}, {@code busy_timeout=5000},
 * {@code foreign_keys=ON}. Reads use a small pool; writes use one dedicated connection, one JDBC
 * transaction per unit of work.
 */
public final class JdbcSql implements Sql, SqlAdmin, AutoCloseable {

  private final WriterLane lane;
  private final LeaseHolder lease;
  private final Connection writerConnection;
  private final JdbcClient writer;
  private final TransactionTemplate tx;
  private final HikariDataSource readers;
  private final JdbcClient reader;
  private final ReentrantLock writeLock = new ReentrantLock();

  private JdbcSql(Path file, int readPoolSize, WriterLane lane, LeaseHolder lease)
      throws SQLException {
    this.lane = lane;
    this.lease = lease;
    String url = "jdbc:sqlite:" + file.toAbsolutePath();

    // The writer opens first, so the file and its WAL exist before any reader connects.
    this.writerConnection = dataSource(url).getConnection();
    var writerSource = new SingleConnectionDataSource(writerConnection, true);
    this.writer = JdbcClient.create(writerSource);
    this.tx = new TransactionTemplate(new DataSourceTransactionManager(writerSource));

    var pool = new HikariConfig();
    pool.setDataSource(dataSource(url));
    pool.setMaximumPoolSize(readPoolSize);
    pool.setPoolName("sqlite-read");
    this.readers = new HikariDataSource(pool);
    this.reader = JdbcClient.create(readers);
  }

  public static JdbcSql open(Path file, int readPoolSize, WriterLane lane, LeaseHolder lease) {
    try {
      var parent = file.toAbsolutePath().getParent();
      if (parent != null) Files.createDirectories(parent);
      return new JdbcSql(file, readPoolSize, lane, lease);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (SQLException e) {
      throw new IllegalStateException("cannot open SQLite database " + file, e);
    }
  }

  private static SQLiteDataSource dataSource(String url) {
    var config = new SQLiteConfig();
    config.setJournalMode(SQLiteConfig.JournalMode.WAL);
    config.setBusyTimeout(5000);
    config.enforceForeignKeys(true);
    var ds = new SQLiteDataSource(config);
    ds.setUrl(url);
    return ds;
  }

  @Override
  public <T> List<T> query(String sql, List<?> args, RowMapper<T> mapper) {
    return reader
        .sql(sql)
        .params(SqlArgs.normalizeAll(args))
        .query((rs, n) -> mapper.map(new JdbcRow(rs)))
        .list();
  }

  @Override
  public <T> Optional<T> queryOne(String sql, List<?> args, RowMapper<T> mapper) {
    var rows = query(sql, args, mapper);
    if (rows.size() > 1) {
      throw new IllegalStateException("expected at most one row, got " + rows.size() + ": " + sql);
    }
    return rows.stream().findFirst();
  }

  @Override
  public void commit(UnitOfWork uow) {
    lane.checkOnLane();
    var fence =
        lease
            .current()
            .orElseThrow(() -> new LeaseLostException("this process doesn't hold the writer lease"));
    if (uow.isEmpty()) return;
    try {
      run(uow, fence);
    } catch (DataAccessException e) {
      var cause = e.getMostSpecificCause().getMessage();
      if (cause != null && cause.contains(Schema.FENCE_VIOLATION)) {
        lease.clearIf(fence);
        throw new LeaseLostException(
            "unit aborted: lease epoch " + fence.epoch() + " is no longer current", e);
      }
      throw e;
    }
  }

  @Override
  public void execute(UnitOfWork uow) {
    if (!uow.isEmpty()) run(uow, null);
  }

  private void run(UnitOfWork uow, LeaseHolder.Fence fence) {
    writeLock.lock();
    try {
      tx.executeWithoutResult(
          status -> {
            if (fence != null) {
              writer.sql(Schema.FENCE_SQL).params(Schema.WORLD_ID, fence.owner(), fence.epoch()).update();
            }
            for (var s : uow.statements()) {
              writer.sql(s.sql()).params(s.args()).update();
            }
          });
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void close() {
    readers.close();
    writeLock.lock();
    try {
      writerConnection.close();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    } finally {
      writeLock.unlock();
    }
  }
}
