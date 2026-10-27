package app.radiance.content;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Reads the shipped content from {@code classpath:content/}. */
public final class ClasspathContent {

  private ClasspathContent() {}

  public static ContentLibrary load(List<ContentProperties.BlacklistEntry> blacklist) {
    return ContentLoader.load(files("content/"), blacklist);
  }

  /** Every {@code .yaml} under {@code root}, keyed by its path relative to it. */
  public static Map<String, String> files(String root) {
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
