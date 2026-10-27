package app.radiance.llm;

import java.util.PriorityQueue;

/**
 * A provider's concurrency cap where waiting live calls go first (SPEC §5.10), FIFO within a
 * priority. Works with virtual threads: waiters park on a monitor only while a slot is taken.
 */
final class PrioritySemaphore {

  private record Waiter(int priority, long seq) implements Comparable<Waiter> {
    @Override
    public int compareTo(Waiter o) {
      int c = Integer.compare(priority, o.priority);
      return c != 0 ? c : Long.compare(seq, o.seq);
    }
  }

  private final PriorityQueue<Waiter> queue = new PriorityQueue<>();
  private int available;
  private long nextSeq;

  PrioritySemaphore(int permits) {
    this.available = permits;
  }

  synchronized void acquire(Priority priority) throws InterruptedException {
    var me = new Waiter(priority.ordinal(), nextSeq++);
    queue.add(me);
    try {
      while (available == 0 || queue.peek() != me) wait();
    } catch (InterruptedException e) {
      queue.remove(me);
      notifyAll();
      throw e;
    }
    queue.poll();
    available--;
    notifyAll();
  }

  synchronized void release() {
    available++;
    notifyAll();
  }

  synchronized int waiting() {
    return queue.size();
  }
}
