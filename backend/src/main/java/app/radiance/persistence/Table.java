package app.radiance.persistence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A table's mapping in one place: column names next to their getters, the key, and the row mapper.
 * Writes are retry-safe upserts of absolute values (SPEC §4.2).
 */
public final class Table<R> {

  public record Col<R>(String name, Function<R, ?> get) {}

  public static <R> Col<R> col(String name, Function<R, ?> get) {
    return new Col<>(name, get);
  }

  private final String name;
  private final List<String> key;
  private final List<Col<R>> cols;
  private final List<String> colNames;
  private final RowMapper<R> mapper;
  private final String select;

  @SafeVarargs
  @SuppressWarnings("varargs") // the array is copied immediately and never exposed
  public Table(String name, List<String> key, RowMapper<R> mapper, Col<R>... cols) {
    this.name = name;
    this.key = List.copyOf(key);
    this.cols = List.copyOf(Arrays.asList(cols));
    this.colNames = this.cols.stream().map(Col::name).toList();
    this.mapper = mapper;
    this.select = "SELECT " + String.join(", ", colNames) + " FROM " + name;
    if (!colNames.containsAll(key)) {
      throw new IllegalArgumentException(name + ": key " + key + " not among columns");
    }
  }

  public String name() {
    return name;
  }

  public List<String> columns() {
    return colNames;
  }

  public UnitOfWork upsert(UnitOfWork uow, R row) {
    return upsert(uow, List.of(row));
  }

  public UnitOfWork upsert(UnitOfWork uow, Collection<? extends R> rows) {
    return rows.isEmpty() ? uow : uow.upsertRows(name, colNames, key, values(rows));
  }

  /** Insert-only ({@code ON CONFLICT DO NOTHING}), for append-only rows. */
  public UnitOfWork insert(UnitOfWork uow, Collection<? extends R> rows) {
    return rows.isEmpty() ? uow : uow.insertRows(name, colNames, values(rows));
  }

  public List<R> all(Sql sql) {
    return sql.query(select, List.of(), mapper);
  }

  /** Rows matching a WHERE clause (and optional ORDER BY) with positional arguments. */
  public List<R> where(Sql sql, String clause, Object... args) {
    return sql.query(select + " WHERE " + clause, Arrays.asList(args), mapper);
  }

  public Optional<R> one(Sql sql, String clause, Object... args) {
    return sql.queryOne(select + " WHERE " + clause, Arrays.asList(args), mapper);
  }

  /** One value list per row, in column order. Values may be null, so no {@code Stream.toList()}. */
  private List<List<Object>> values(Collection<? extends R> rows) {
    var out = new ArrayList<List<Object>>(rows.size());
    for (R r : rows) {
      var vals = new ArrayList<Object>(cols.size());
      for (var c : cols) vals.add(c.get().apply(r));
      out.add(vals);
    }
    return out;
  }
}
