package app.radiance.llm.budget;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Where budget windows survive restarts: the {@code budget_window} table. */
public interface BudgetStore {

  /** One window as stored. Times are wall time (ADR 0010). */
  record Row(String id, double used, double limit, long resetsAt, Long closedUntil) {}

  List<Row> load();

  void save(List<Row> rows);

  void delete(String id);

  /** Keeps rows in memory; for tests. */
  final class InMemory implements BudgetStore {
    private final Map<String, Row> rows = new ConcurrentHashMap<>();

    @Override
    public List<Row> load() {
      return List.copyOf(rows.values());
    }

    @Override
    public void save(List<Row> rows) {
      rows.forEach(r -> this.rows.put(r.id(), r));
    }

    @Override
    public void delete(String id) {
      rows.remove(id);
    }
  }
}
