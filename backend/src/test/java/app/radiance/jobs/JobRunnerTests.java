package app.radiance.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.llm.BudgetExhaustedException;
import app.radiance.persistence.TestDatabase;
import app.radiance.persistence.UnitOfWork;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The job queue (SPEC §5.15): priority order, idempotent enqueue, parking at a budget, retries. */
class JobRunnerTests {

  @TempDir Path dir;
  TestDatabase db;
  JobRepository repo;

  @BeforeEach
  void open() {
    db = TestDatabase.leased(dir);
    repo = new JobRepository(db.sql);
  }

  @AfterEach
  void close() {
    db.close();
  }

  void enqueue(String kind, String ref, int priority, long runAfter) {
    db.commit(repo.enqueue(new UnitOfWork(), kind, ref, priority, runAfter, db.clock.nowMs()));
  }

  @Test
  void runsBestPriorityFirstAndEnqueueingTwiceChangesNothing() {
    long now = db.clock.nowMs();
    enqueue("A", "late", 2, now);
    enqueue("A", "first", 0, now);
    enqueue("A", "second", 1, now);
    enqueue("A", "first", 0, now); // already active
    enqueue("A", "future", 0, now + 60_000);
    assertThat(db.count("job")).isEqualTo(4);
    var order = Collections.synchronizedList(new ArrayList<String>());
    var report = new JobRunner(db.sql, db.lane, db.clock, 1).on("A", j -> order.add(j.refId())).drain();
    assertThat(order).containsExactly("first", "second", "late");
    assertThat(report.done()).isEqualTo(3);
    assertThat(repo.active("A", "future")).isPresent();
    // a finished job can be enqueued again (a re-render), and it runs
    enqueue("A", "first", 0, now);
    assertThat(new JobRunner(db.sql, db.lane, db.clock, 1).on("A", j -> order.add(j.refId())).drain().done()).isEqualTo(1);
  }

  @Test
  void aJobThatHitsABudgetParksUntilTheResetAndThenFinishes() {
    long now = db.clock.nowMs();
    enqueue("R", "thread-1", 0, now);
    enqueue("R", "thread-2", 0, now);
    long reset = now + 3_600_000;
    var open = new java.util.concurrent.atomic.AtomicBoolean(false);
    var ran = new AtomicInteger();
    var runner = new JobRunner(db.sql, db.lane, db.clock, 2).on("R", j -> {
      if (!open.get()) throw new BudgetExhaustedException("usd:daily", reset);
      ran.incrementAndGet();
    });
    var first = runner.drain();
    assertThat(first.parked()).isEqualTo(2);
    assertThat(repo.byStatus(JobRow.Status.WAITING)).allSatisfy(j -> {
      assertThat(j.waitReason()).isEqualTo("usd:daily");
      assertThat(j.runAfter()).isEqualTo(reset);
      assertThat(j.attempts()).isZero(); // parking isn't a failed attempt
    });
    open.set(true);
    assertThat(runner.drain().done()).isZero(); // not reset yet
    db.clock.set(reset);
    assertThat(runner.drain().done()).isEqualTo(2);
    assertThat(ran.get()).isEqualTo(2);
    assertThat(repo.count(JobRow.Status.DONE)).isEqualTo(2);
  }

  @Test
  void failuresRetryWithBackoffThenFailForGood() {
    long now = db.clock.nowMs();
    enqueue("F", "broken", 0, now);
    var runner = new JobRunner(db.sql, db.lane, db.clock, 1).on("F", j -> {
      throw new IllegalStateException("boom");
    });
    for (int i = 0; i < JobRunner.MAX_ATTEMPTS; i++) {
      runner.drain();
      db.clock.set(db.clock.nowMs() + 10 * 60_000);
    }
    var job = repo.byStatus(JobRow.Status.FAILED);
    assertThat(job).hasSize(1);
    assertThat(job.getFirst().attempts()).isEqualTo(JobRunner.MAX_ATTEMPTS);
    assertThat(job.getFirst().lastError()).contains("boom");
  }

  @Test
  void aJobACrashedRunnerHeldGoesBackInLine() {
    long now = db.clock.nowMs();
    enqueue("C", "stuck", 0, now);
    var id = repo.ready(now, 1).getFirst().id();
    db.commit(repo.claim(new UnitOfWork(), id, now)); // and then the process died
    db.clock.set(now + JobRunner.STALE_MS + 1);
    var seen = new ArrayList<String>();
    assertThat(new JobRunner(db.sql, db.lane, db.clock, 1).on("C", j -> seen.add(j.refId())).drain().done()).isEqualTo(1);
    assertThat(seen).isEqualTo(List.of("stuck"));
  }
}
