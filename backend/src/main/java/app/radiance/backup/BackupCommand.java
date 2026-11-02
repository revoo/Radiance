package app.radiance.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The one-shot {@code backup} run mode (ADR 0019), safe while the app is running. Exits 0 when the
 * snapshot reached R2, 3 when it's only on this server for lack of keys, 1 on any failure, so a person or
 * a timer can tell the difference.
 */
@Component
public class BackupCommand implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BackupCommand.class);

  public static boolean isBackupMode(String[] args) {
    return args.length > 0 && "backup".equals(args[0]);
  }

  private final BackupService backups;
  private final ConfigurableApplicationContext context;

  BackupCommand(BackupService backups, ConfigurableApplicationContext context) {
    this.backups = backups;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    if (!isBackupMode(arguments.getSourceArgs())) return;
    var r = backups.run();
    log.info("Backup {}: {} ({} bytes){}{}", r.outcome(), r.file(), r.bytes(), r.offsiteKey() == null ? "" : ", uploaded to " + r.offsiteKey(),
        r.error() == null ? "" : ": " + r.error());
    int code = switch (r.outcome()) {
      case DONE -> 0;
      case LOCAL_ONLY -> 3;
      case OFFSITE_FAILED, FAILED -> 1;
    };
    System.exit(SpringApplication.exit(context, () -> code));
  }
}
