package app.radiance.persistence;

@FunctionalInterface
public interface RowMapper<T> {
  T map(Row row);
}
