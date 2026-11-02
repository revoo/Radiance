package app.radiance.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.radiance.kernel.ManualClock;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** With no R2 keys: loud in the log at startup, and in what the SPA reads for its banner. */
@SpringBootTest(properties = {"radiance.backup.enabled=true", "radiance.backup.dir=${java.io.tmpdir}/radiance-test/backup-status",
    "radiance.backup.r2.access-key-id=", "radiance.backup.r2.secret-access-key=", "radiance.backup.r2.bucket=",
    "radiance.backup.r2.account-id=", "radiance.backup.r2.endpoint="})
@AutoConfigureMockMvc
@ActiveProfiles({"offline", "test"})
@ExtendWith(OutputCaptureExtension.class)
class BackupStatusTests {

  @Autowired MockMvc mvc;

  @Test
  void theSpaIsToldWhenBackupsCantLeaveTheServer() throws Exception {
    mvc.perform(get("/api/director/backups").with(user("r"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.offsiteConfigured").value(false))
        .andExpect(jsonPath("$.warning").value(org.hamcrest.Matchers.containsString("aren't leaving this server")));
    mvc.perform(get("/api/director/backups")).andExpect(status().isUnauthorized());
  }

  /** The log says so the moment the scheduler starts, not just at the first nightly run. */
  @Test
  void startingWithoutKeysIsLoudInTheLog(CapturedOutput output, @TempDir Path dir) {
    var svc = new BackupService(dir.resolve("radiance.db"), dir.resolve("backups"), R2Settings.from(Map.of()), 14, 8, 3, new ManualClock(0));
    var scheduler = new BackupScheduler(svc, "30 8 * * *", new ManualClock(0), true);
    scheduler.start();
    scheduler.stop();
    assertThat(output.getOut() + output.getErr()).contains("BACKUPS ARE NOT LEAVING THIS SERVER").contains("R2_ACCESS_KEY_ID");
  }

  @Test
  void aSchedulerThatsOffSaysNothing(CapturedOutput output, @TempDir Path dir) {
    var svc = new BackupService(dir.resolve("radiance.db"), dir.resolve("backups"), R2Settings.from(Map.of()), 14, 8, 3, new ManualClock(0));
    var scheduler = new BackupScheduler(svc, "30 8 * * *", new ManualClock(0), false);
    int before = (output.getOut() + output.getErr()).length();
    scheduler.start();
    assertThat(scheduler.isRunning()).isFalse();
    assertThat((output.getOut() + output.getErr()).substring(before)).doesNotContain("BACKUPS ARE NOT LEAVING");
  }
}
