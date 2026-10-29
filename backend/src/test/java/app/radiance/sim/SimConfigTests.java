package app.radiance.sim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** The knobs come from {@code radiance.*} (SPEC §9) and match the spec's defaults out of the box. */
@SpringBootTest
@ActiveProfiles({"offline", "test"})
class SimConfigTests {

  @Autowired Knobs knobs;
  @Autowired WorldEngine engine;

  @Test
  void theConfiguredKnobsAreTheSpecDefaults() {
    assertThat(knobs).isEqualTo(Knobs.defaults());
    assertThat(engine.knobs()).isSameAs(knobs);
  }
}
