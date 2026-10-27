package app.radiance.llm.budget;

import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persists budget windows through the writer lane, without making the caller wait. */
public final class SqlBudgetStore implements BudgetStore {

  private static final Logger log = LoggerFactory.getLogger(SqlBudgetStore.class);
  private static final List<String> COLS = List.of("id", "used", "limit_value", "resets_at", "closed_until");

  private final Sql sql;
  private final WriterLane lane;

  public SqlBudgetStore(Sql sql, WriterLane lane) {
    this.sql = sql;
    this.lane = lane;
  }

  @Override
  public List<Row> load() {
    return sql.query("SELECT id, used, limit_value, resets_at, closed_until FROM budget_window",
        List.of(), r -> new Row(r.str("id"), r.dbl("used"), r.dbl("limit_value"), r.lng("resets_at"),
            r.lngOrNull("closed_until")));
  }

  @Override
  public void save(List<Row> rows) {
    if (rows.isEmpty()) return;
    var values = rows.stream()
        .map(r -> java.util.Arrays.<Object>asList(r.id(), r.used(), r.limit(), r.resetsAt(), r.closedUntil()))
        .toList();
    submit(new UnitOfWork().upsertRows("budget_window", COLS, List.of("id"), values));
  }

  @Override
  public void delete(String id) {
    submit(new UnitOfWork().add("DELETE FROM budget_window WHERE id = ?", id));
  }

  private void submit(UnitOfWork uow) {
    lane.submit(() -> sql.commit(uow)).whenComplete((v, e) -> {
      if (e != null) log.warn("Could not persist budget windows: {}", e.toString());
    });
  }
}
