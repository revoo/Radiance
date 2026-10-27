package app.radiance.persistence;

import java.util.List;
import java.util.Optional;

/**
 * The persistence port (SPEC §4.2). Reads may run on any thread. {@link #commit} is world-state
 * mutation: it runs only on the writer lane, under the writer lease, and every unit is fenced so a
 * process that lost the lease can't write.
 */
public interface Sql {

  <T> List<T> query(String sql, List<?> args, RowMapper<T> mapper);

  <T> Optional<T> queryOne(String sql, List<?> args, RowMapper<T> mapper);

  /** Atomic; only callable from the writer lane while holding the writer lease. */
  void commit(UnitOfWork uow);
}
