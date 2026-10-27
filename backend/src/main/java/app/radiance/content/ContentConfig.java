package app.radiance.content;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Loads {@code classpath:content/**} once at startup; invalid content fails startup (SPEC P7). */
@Configuration(proxyBeanMethods = false)
class ContentConfig {

  @Bean
  ContentLibrary contentLibrary(ContentProperties props) {
    return ClasspathContent.load(props.arcBlacklist());
  }
}
