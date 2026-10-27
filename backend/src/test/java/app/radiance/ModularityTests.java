package app.radiance;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Module boundaries (SPEC §3.5, §10): Spring Modulith is used for verification only. */
class ModularityTests {

  @Test
  void moduleBoundariesHold() {
    ApplicationModules.of(RadianceApplication.class).verify();
  }
}
