package app.radiance.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class UnitOfWorkTests {

  @Property
  void insertRowsChunksToTheParameterLimitAndKeepsEveryValueInOrder(
      @ForAll @IntRange(min = 1, max = 100) int cols, @ForAll @IntRange(max = 400) int rows) {
    var names = new ArrayList<String>();
    for (int c = 0; c < cols; c++) names.add("c" + c);
    var data = new ArrayList<List<Object>>();
    for (int r = 0; r < rows; r++) {
      var row = new ArrayList<Object>();
      for (int c = 0; c < cols; c++) row.add((long) r * 1000 + c);
      data.add(row);
    }

    var uow = new UnitOfWork().insertRows("t", names, data);

    var flattened = new ArrayList<Object>();
    for (var s : uow.statements()) {
      assertThat(s.args()).hasSizeLessThanOrEqualTo(UnitOfWork.MAX_PARAMS);
      assertThat(s.args().size() % cols).isZero();
      assertThat(s.sql()).endsWith("ON CONFLICT DO NOTHING");
      flattened.addAll(s.args());
    }
    assertThat(flattened).isEqualTo(data.stream().flatMap(List::stream).toList());
    int perStatement = UnitOfWork.MAX_PARAMS / cols;
    assertThat(uow.size()).isEqualTo((rows + perStatement - 1) / perStatement);
  }

  @Test
  void upsertOverwritesOnlyNonKeyColumns() {
    var uow = new UnitOfWork()
        .upsertRows("relationship", List.of("from_id", "to_id", "affinity"),
            List.of("from_id", "to_id"), List.of(List.of("a", "b", 12.5)));
    assertThat(uow.statements().getFirst().sql())
        .isEqualTo("INSERT INTO relationship (from_id, to_id, affinity) VALUES (?, ?, ?)"
            + " ON CONFLICT (from_id, to_id) DO UPDATE SET affinity = excluded.affinity");

    var allKeys = new UnitOfWork()
        .upsertRows("k", List.of("a", "b"), List.of("a", "b"), List.of(List.of(1, 2)));
    assertThat(allKeys.statements().getFirst().sql()).endsWith("ON CONFLICT (a, b) DO NOTHING");
  }

  @Test
  void normalizesArgumentsToWhatD1Stores() {
    var uow = new UnitOfWork().add("x", true, false, 3, Thread.State.RUNNABLE, null, 1.5f);
    assertThat(uow.statements().getFirst().args())
        .containsExactly(1L, 0L, 3L, "RUNNABLE", null, 1.5);
    assertThatThrownBy(() -> new UnitOfWork().add("x", java.time.Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void enforcesD1StatementLimits() {
    var tooMany = Collections.nCopies(101, (Object) 1L).toArray();
    assertThatThrownBy(() -> new UnitOfWork().add("x", tooMany))
        .hasMessageContaining("101 parameters");
    assertThatThrownBy(() -> new UnitOfWork().add("x".repeat(100_001)))
        .hasMessageContaining("bytes");
    assertThatThrownBy(() -> new UnitOfWork().insertRows("t; DROP", List.of("a"), List.of()))
        .hasMessageContaining("identifier");
    assertThatThrownBy(() -> new UnitOfWork().insertRows("t", List.of("a", "b"),
            List.of(List.of(1))))
        .hasMessageContaining("1 values for 2 columns");
  }
}
