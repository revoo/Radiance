package app.radiance.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AvatarPromptTests {

  private static final LocalDate TODAY = LocalDate.parse("2026-11-03");

  /** Regression: genesis writes every resident a canonical look, and the avatar prompt never read it. */
  @Test
  void theAvatarIsBuiltFromTheirAuthoredLook() {
    String look = "38-year-old woman, tall and wiry, dark blonde hair in a practical braid.";
    String prompt = ImageWork.avatarPrompt(new String[] {"id", "F", "1988-01-01", "Nurse", "{\"appearance\":\"" + look + "\"}"}, TODAY);
    assertThat(prompt).contains(look).contains("adult").contains("not anyone real");
  }

  @Test
  void withNoAuthoredLook_ItFallsBackOnTheFacts() {
    String prompt = ImageWork.avatarPrompt(new String[] {"id", "M", "1996-01-01", "Electrician", "{\"heritage\":\"Polish\"}"}, TODAY);
    assertThat(prompt).contains("30-year-old Polish man", "works as: electrician","not anyone real");
  }
}
