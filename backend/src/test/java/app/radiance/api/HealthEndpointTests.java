package app.radiance.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** M0 AC: the offline profile boots with no environment variables and health is UP. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"offline", "test"})
class HealthEndpointTests {

  @Autowired MockMvc mvc;

  @Test
  void healthIsUpWithoutAuthentication() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void everythingElseNeedsALogin() throws Exception {
    mvc.perform(get("/api/me")).andExpect(status().is3xxRedirection());
  }
}
