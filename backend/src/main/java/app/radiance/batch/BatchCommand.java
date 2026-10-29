package app.radiance.batch;

import app.radiance.persistence.LeaseHolder;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The one-shot {@code batch nightly} run mode (SPEC §5.1, §12 M6): {@code ./gradlew batch}, or an OS
 * timer on R's machine. Waits briefly for the writer lease; if the running app holds it, the run exits
 * DEFERRED and the app runs the batch in-process. Exits 0 unless the batch failed.
 */
@Component
public class BatchCommand implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BatchCommand.class);

  public static boolean isBatchMode(String[] args) {
    return "batch".equals(System.getProperty("radiance.mode")) || (args.length > 0 && "batch".equals(args[0]));
  }

  private final NightlyBatch batch;
  private final LeaseHolder lease;
  private final ConfigurableApplicationContext context;

  BatchCommand(NightlyBatch batch, LeaseHolder lease, ConfigurableApplicationContext context) {
    this.batch = batch;
    this.lease = lease;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments arguments) throws InterruptedException {
    String[] args = arguments.getSourceArgs();
    if (!isBatchMode(args)) return;
    var rest = Arrays.stream(args).filter(a -> !a.equals("batch")).toList();
    String what = rest.isEmpty() ? "nightly" : rest.getFirst();
    int code = 0;
    if (!what.equals("nightly")) {
      log.error("Usage: batch nightly");
      code = 2;
    } else {
      // the lease keeper tries at startup; give it a few seconds before concluding another process has it
      for (int i = 0; i < 50 && !lease.isHeld(); i++) Thread.sleep(100);
      var report = batch.run(NightlyBatch.Kind.NIGHTLY);
      log.info("Nightly batch {}: planned {}, rendered {}, parked {}, cost ${} {}", report.status(), report.planned(),
          report.rendered(), report.parked(), String.format("%.4f", report.costUsd()), report.notes());
      if (report.status().equals("FAILED")) code = 1;
    }
    int exit = code;
    System.exit(SpringApplication.exit(context, () -> exit));
  }
}
