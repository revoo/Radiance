package app.radiance.jobs;

import app.radiance.kernel.WallClock;
import app.radiance.llm.BudgetExhaustedException;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs durable jobs (SPEC §5.15): best priority first, a few at a time on virtual threads. A job that
 * hits a closed budget window is parked as WAITING until its reset, and the runner moves on to work
 * that fits; a job that fails is retried with backoff, then marked FAILED. Handlers are idempotent, so
 * a job cut off mid-run loses only that run.
 */
public final class JobRunner {

  /** Does one job's work. Throws {@link BudgetExhaustedException} to park, anything else to retry. */
  public interface Handler {
    void run(JobRow job) throws Exception;
  }

  /** What one {@link #drain} did. */
  public record Report(int done, int parked, int retried, int failed) {}

  private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
  static final int MAX_ATTEMPTS = 3;
  static final long STALE_MS = 10 * 60_000L;

  private final Sql sql;
  private final WriterLane lane;
  private final WallClock clock;
  private final JobRepository repo;
  private final Map<String, Handler> handlers = new HashMap<>();
  private final int parallelism;

  public JobRunner(Sql sql, WriterLane lane, WallClock clock, int parallelism) {
    this.sql = sql;
    this.lane = lane;
    this.clock = clock;
    this.repo = new JobRepository(sql);
    this.parallelism = Math.max(1, parallelism);
  }

  public JobRunner on(String kind, Handler handler) {
    handlers.put(kind, handler);
    return this;
  }

  /**
   * Runs ready jobs until none are left that can run now. Each round takes the best {@code parallelism}
   * ready jobs and runs them together, so priority and air-time order hold across rounds.
   */
  public Report drain() {
    int done = 0, parked = 0, retried = 0, failed = 0;
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      while (true) {
        long now = clock.nowMs();
        lane.run(() -> sql.commit(repo.wake(new UnitOfWork(), now, now - STALE_MS)));
        var batch = repo.ready(now, parallelism).stream().filter(j -> handlers.containsKey(j.kind())).toList();
        if (batch.isEmpty()) break;
        var futures = new ArrayList<Future<Outcome>>();
        for (var job : batch) {
          lane.run(() -> sql.commit(repo.claim(new UnitOfWork(), job.id(), now)));
          futures.add(pool.submit(() -> runOne(job)));
        }
        for (var f : futures) {
          switch (get(f)) {
            case DONE -> done++;
            case PARKED -> parked++;
            case RETRIED -> retried++;
            case FAILED -> failed++;
          }
        }
      }
    }
    return new Report(done, parked, retried, failed);
  }

  enum Outcome { DONE, PARKED, RETRIED, FAILED }

  private Outcome runOne(JobRow job) {
    try {
      handlers.get(job.kind()).run(job);
      lane.run(() -> sql.commit(repo.done(new UnitOfWork(), job.id())));
      return Outcome.DONE;
    } catch (BudgetExhaustedException e) {
      lane.run(() -> sql.commit(repo.park(new UnitOfWork(), job.id(), e.windowId(), e.resumeAt())));
      return Outcome.PARKED;
    } catch (Exception e) {
      int attempts = job.attempts() + 1;
      String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
      if (attempts >= MAX_ATTEMPTS) {
        log.warn("Job {} {} failed for good: {}", job.kind(), job.refId(), msg);
        lane.run(() -> sql.commit(repo.fail(new UnitOfWork(), job.id(), msg)));
        return Outcome.FAILED;
      }
      long backoff = 30_000L << (attempts - 1);
      lane.run(() -> sql.commit(repo.retry(new UnitOfWork(), job.id(), clock.nowMs() + backoff, msg)));
      return Outcome.RETRIED;
    }
  }

  private static Outcome get(Future<Outcome> f) {
    try {
      return f.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (java.util.concurrent.ExecutionException e) {
      throw new IllegalStateException(e.getCause());
    }
  }

  public List<String> kinds() {
    return List.copyOf(handlers.keySet());
  }
}
