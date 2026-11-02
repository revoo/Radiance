package app.radiance.backup;

import app.radiance.kernel.WallClock;
import app.radiance.persistence.PersistenceProperties;
import java.nio.file.Path;
import java.util.HashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Backups (ADR 0019): off unless the profile turns them on; one-shot run modes never schedule them. */
@Configuration(proxyBeanMethods = false)
class BackupConfig {

  @Bean
  BackupService backupService(PersistenceProperties persistence, WallClock clock, @Value("${radiance.backup.dir}") String dir,
      @Value("${radiance.backup.keep-daily}") int daily, @Value("${radiance.backup.keep-weekly}") int weekly,
      @Value("${radiance.backup.keep-local}") int local, @Value("${radiance.backup.r2.endpoint:}") String endpoint,
      @Value("${radiance.backup.r2.account-id:}") String account, @Value("${radiance.backup.r2.access-key-id:}") String key,
      @Value("${radiance.backup.r2.secret-access-key:}") String secret, @Value("${radiance.backup.r2.bucket:}") String bucket,
      @Value("${radiance.backup.r2.region:auto}") String region) {
    var env = new HashMap<String, String>();
    env.put("R2_ENDPOINT", endpoint);
    env.put("CF_ACCOUNT_ID", account);
    env.put("R2_ACCESS_KEY_ID", key);
    env.put("R2_SECRET_ACCESS_KEY", secret);
    env.put("R2_BACKUP_BUCKET", bucket);
    env.put("R2_REGION", region);
    return new BackupService(Path.of(persistence.dbPath()), Path.of(dir), R2Settings.from(env), daily, weekly, local, clock);
  }

  @Bean
  BackupScheduler backupScheduler(BackupService backups, WallClock clock, @Value("${radiance.backup.cron}") String cron,
      @Value("${radiance.backup.enabled:false}") boolean enabled) {
    boolean oneShot = System.getProperty("radiance.mode") != null;
    return new BackupScheduler(backups, cron, clock, enabled && !oneShot);
  }
}
