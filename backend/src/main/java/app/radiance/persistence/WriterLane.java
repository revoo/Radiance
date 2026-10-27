package app.radiance.persistence;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The single writer lane (SPEC P4): one dedicated platform thread on which every world-state mutation
 * runs. {@link Sql#commit} refuses to run anywhere else. The WorldEngine's event loop (M4) runs here.
 */
public final class WriterLane implements AutoCloseable {

  private final ScheduledExecutorService executor;
  private volatile Thread thread;

  public WriterLane(String name) {
    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, name);
              t.setDaemon(true);
              thread = t;
              return t;
            });
  }

  public <T> CompletableFuture<T> submit(Callable<T> task) {
    var future = new CompletableFuture<T>();
    executor.execute(
        () -> {
          try {
            future.complete(task.call());
          } catch (Throwable e) {
            future.completeExceptionally(e);
          }
        });
    return future;
  }

  public CompletableFuture<Void> submit(Runnable task) {
    return submit(
        () -> {
          task.run();
          return null;
        });
  }

  /** Runs the task on the lane and waits for it; runs inline if already on the lane. */
  public <T> T call(Callable<T> task) {
    if (isCurrentThread()) {
      try {
        return task.call();
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    try {
      return submit(task).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for the writer lane", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException re) throw re;
      if (e.getCause() instanceof Error err) throw err;
      throw new IllegalStateException(e.getCause());
    }
  }

  public void run(Runnable task) {
    call(
        () -> {
          task.run();
          return null;
        });
  }

  public boolean isCurrentThread() {
    return Thread.currentThread() == thread;
  }

  /** Fails fast when called off the lane. */
  public void checkOnLane() {
    if (!isCurrentThread()) {
      throw new IllegalStateException(
          "world-state writes must run on the writer lane, not on "
              + Thread.currentThread().getName());
    }
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
