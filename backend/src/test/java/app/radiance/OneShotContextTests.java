package app.radiance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
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
}
