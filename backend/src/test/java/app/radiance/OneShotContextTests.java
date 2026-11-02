package app.radiance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * The one-shot run modes (genesis, batch, bake-off) start without a web server: nothing that only the
 * web app has (live play, its controllers, security) may be required there.
 */
class OneShotContextTests {

  @Test
  void theAppStartsWithoutAWebServer() {
    try (var ctx = new SpringApplicationBuilder(RadianceApplication.class).web(WebApplicationType.NONE)
        .profiles("offline", "test").run()) {
      assertThat(ctx.isActive()).isTrue();
      assertThat(ctx.getBeanNamesForType(app.radiance.live.LivePlay.class)).isEmpty();
    }
  }

  /**
   * Regression: only the Gradle tasks set radiance.mode, so `docker run IMAGE genesis` also started the
   * in-process batch scheduler alongside the command.
   */
  @Test
  void aModeNamedByTheFirstArgumentIsAOneShotToo() {
    System.clearProperty("radiance.mode");
    try {
      var app = RadianceApplication.configure(new SpringApplication(RadianceApplication.class), new String[] {"genesis", "--seed", "7"});
      assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
      assertThat(System.getProperty("radiance.mode")).isEqualTo("genesis");
    } finally {
      System.clearProperty("radiance.mode");
    }
    var web = RadianceApplication.configure(new SpringApplication(RadianceApplication.class), new String[] {});
    assertThat(web.getWebApplicationType()).isNotEqualTo(WebApplicationType.NONE);
    assertThat(System.getProperty("radiance.mode")).isNull();
  }
}
