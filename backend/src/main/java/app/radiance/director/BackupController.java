package app.radiance.director;

import app.radiance.backup.BackupService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How backups are doing (ADR 0019), for the SPA's banner. {@code warning} is what R needs to act on, and
 * stays null where backups aren't on (development).
 */
@ConditionalOnWebApplication
@RestController
class BackupController {

  record BackupView(boolean enabled, boolean offsiteConfigured, String warning, Long lastAt, Long lastOffsiteAt, String lastFile,
      String lastError) {}

  private final BackupService backups;
  private final boolean enabled;

  BackupController(BackupService backups, @Value("${radiance.backup.enabled:false}") boolean enabled) {
    this.backups = backups;
    this.enabled = enabled;
  }

  @GetMapping("/api/director/backups")
  BackupView backups() {
    var s = backups.status();
    return new BackupView(enabled, backups.offsiteConfigured(), enabled ? backups.warning() : null, s.lastAt(), s.lastOffsiteAt(),
        s.lastFile(), s.lastError());
  }
}
