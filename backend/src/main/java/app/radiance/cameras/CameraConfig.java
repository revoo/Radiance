package app.radiance.cameras;

import app.radiance.kernel.WallClock;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** The left rail's cameras (ADR 0020): off unless {@code radiance.cameras.enabled}, as in {@code prod}. */
@Configuration(proxyBeanMethods = false)
class CameraConfig {

  @Bean
  CameraFeed cameraFeed(Environment env, WallClock clock, @Value("${radiance.cameras.enabled:false}") boolean enabled,
      @Value("${radiance.cameras.dataset}") URI dataset, @Value("${radiance.cameras.image-origin}") String imageOrigin) {
    List<CameraFeed.Spot> spots = Binder.get(env).bind("radiance.cameras.spots", Bindable.listOf(CameraFeed.Spot.class)).orElse(List.of());
    return new CameraFeed(enabled, dataset, imageOrigin, spots, clock);
  }
}
