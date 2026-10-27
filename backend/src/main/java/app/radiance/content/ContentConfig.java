package app.radiance.content;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Loads {@code classpath:content/**} once at startup; invalid content fails startup (SPEC P7). */
@Configuration(proxyBeanMethods = false)
class ContentConfig {

  @Bean
  ContentLibrary contentLibrary(ContentProperties props) {
    return ContentLoader.load(classpathFiles("content/"), props.arcBlacklist());
  }

  /** Every {@code .yaml} under {@code root}, keyed by its path relative to it. */
  static Map<String, String> classpathFiles(String root) {
    try {
      var out = new HashMap<String, String>();
      var resolver = new PathMatchingResourcePatternResolver();
      String base = resolver.getResource("classpath:" + root).getURL().toString();
      for (var r : resolver.getResources("classpath*:" + root + "**/*.yaml")) {
        String url = r.getURL().toString();
        String rel = url.startsWith(base) ? url.substring(base.length())
            : url.substring(url.lastIndexOf(root) + root.length());
        out.put(rel, r.getContentAsString(StandardCharsets.UTF_8));
      }
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
