package app.radiance.persistence;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Ordered statements executed atomically (SPEC §4.2). Enforces D1's limits as statements are added:
 * at most {@value #MAX_PARAMS} bound parameters and {@value #MAX_STATEMENT_BYTES} bytes of SQL per
 * statement. Multi-row helpers chunk automatically and are retry-safe by construction.
 */
public final class UnitOfWork {

  public static final int MAX_PARAMS = 100;
  public static final int MAX_STATEMENT_BYTES = 100_000;

  private static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]*");

  public record Statement(String sql, List<Object> args) {}

  private final List<Statement> statements = new ArrayList<>();

  public UnitOfWork add(String sql, Object... args) {
    var list = args == null ? List.of() : Arrays.asList(args);
    if (list.size() > MAX_PARAMS) {
      throw new IllegalArgumentException(
          "statement has " + list.size() + " parameters; D1 allows " + MAX_PARAMS);
    }
    if (sql.getBytes(StandardCharsets.UTF_8).length > MAX_STATEMENT_BYTES) {
      throw new IllegalArgumentException("statement exceeds " + MAX_STATEMENT_BYTES + " bytes");
    }
    statements.add(new Statement(sql, SqlArgs.normalizeAll(list)));
    return this;
  }

  /**
   * Multi-row {@code INSERT … ON CONFLICT DO NOTHING}, chunked so no statement exceeds
   * {@value #MAX_PARAMS} parameters. Re-running it is a no-op.
   */
  public UnitOfWork insertRows(String table, List<String> cols, List<List<Object>> rows) {
    return multiRow(table, cols, rows, " ON CONFLICT DO NOTHING");
  }

  /**
   * Multi-row upsert: inserts, or overwrites every non-key column with the given absolute values.
   * Chunked like {@link #insertRows}. Re-running it leaves the same state.
   */
  public UnitOfWork upsertRows(
      String table, List<String> cols, List<String> keyCols, List<List<Object>> rows) {
    keyCols.forEach(UnitOfWork::checkIdent);
    if (!cols.containsAll(keyCols)) {
      throw new IllegalArgumentException("key columns must be among the columns: " + keyCols);
    }
    var updates = cols.stream().filter(c -> !keyCols.contains(c)).toList();
    var tail =
        updates.isEmpty()
            ? " ON CONFLICT (" + String.join(", ", keyCols) + ") DO NOTHING"
            : " ON CONFLICT ("
                + String.join(", ", keyCols)
                + ") DO UPDATE SET "
                + String.join(", ", updates.stream().map(c -> c + " = excluded." + c).toList());
    return multiRow(table, cols, rows, tail);
  }

  private UnitOfWork multiRow(
      String table, List<String> cols, List<List<Object>> rows, String conflictClause) {
    checkIdent(table);
    cols.forEach(UnitOfWork::checkIdent);
    if (cols.isEmpty() || cols.size() > MAX_PARAMS) {
      throw new IllegalArgumentException("need 1.." + MAX_PARAMS + " columns, got " + cols.size());
    }
    int perStatement = MAX_PARAMS / cols.size();
    String head = "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES ";
    String tuple = "(" + String.join(", ", Collections.nCopies(cols.size(), "?")) + ")";
    for (int from = 0; from < rows.size(); from += perStatement) {
      var chunk = rows.subList(from, Math.min(rows.size(), from + perStatement));
      var args = new ArrayList<Object>(chunk.size() * cols.size());
      for (var row : chunk) {
        if (row.size() != cols.size()) {
          throw new IllegalArgumentException(
              "row has " + row.size() + " values for " + cols.size() + " columns in " + table);
        }
        args.addAll(row);
      }
      add(head + String.join(", ", Collections.nCopies(chunk.size(), tuple)) + conflictClause,
          args.toArray());
    }
    return this;
  }

  private static void checkIdent(String name) {
    if (!IDENT.matcher(name).matches()) {
      throw new IllegalArgumentException("not a safe SQL identifier: " + name);
    }
  }

  public List<Statement> statements() {
    return Collections.unmodifiableList(statements);
  }

  public boolean isEmpty() {
    return statements.isEmpty();
  }

  public int size() {
    return statements.size();
  }
}
