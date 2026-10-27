package app.radiance.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class PrioritySemaphoreTests {

  @Test
  void liveWaitersGoAheadOfBatchWork() throws Exception {
    var sem = new PrioritySemaphore(1);
    sem.acquire(Priority.SCRIPT); // the only slot is busy
    var order = new CopyOnWriteArrayList<Priority>();

    var waiters = List.of(Priority.BACKGROUND, Priority.SCRIPT, Priority.LIVE).stream()
        .map(p -> Thread.ofVirtual().start(() -> {
          try {
            sem.acquire(p);
            order.add(p);
            sem.release();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }))
        .toList();
    while (sem.waiting() < 3) Thread.sleep(5);

    sem.release();
    for (var t : waiters) t.join();
    assertThat(order).containsExactly(Priority.LIVE, Priority.SCRIPT, Priority.BACKGROUND);
  }
}
