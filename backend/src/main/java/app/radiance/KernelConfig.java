package app.radiance;

import app.radiance.kernel.WallClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans for the plain-Java kernel, which knows nothing of Spring. */
@Configuration(proxyBeanMethods = false)
class KernelConfig {

  @Bean
  WallClock wallClock() {
    return WallClock.system();
  }
}
