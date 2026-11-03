package app.radiance.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stored prompts don't fill the server's disk: the batch keeps two weeks of them. */
class PromptRetentionTests {

  @TempDir Path dir;

  Path day(String name) throws Exception {
    Path d = Files.createDirectories(dir.resolve(name));
    Files.writeString(d.resolve("call.json"), "{}");
    return d;
  }

  @Test
  void removesOnlyDayFoldersPastTheLimit() throws Exception {
    long now = LocalDate.of(2026, 11, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3_600_000;
    Path old = day("2026-11-01");
    Path edge = day("2026-11-06");
    Path recent = day("2026-11-19");
    Path notADay = day("keep-me");

    assertThat(PromptStore.Files.prune(dir, now, 14)).isEqualTo(1);

    assertThat(old).doesNotExist();
    assertThat(edge).as("exactly the limit stays").exists();
    assertThat(recent.resolve("call.json")).exists();
    assertThat(notADay).as("only what it recognizes as a day").exists();
  }

  @Test
  void aMissingFolderIsFine() {
    assertThat(PromptStore.Files.prune(dir.resolve("nope"), 0, 14)).isZero();
  }
}
