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

  /** Every {@code .yaml} and {@code .md} under {@code root}, keyed by its path relative to it. */
  public static Map<String, String> files(String root) {
    return files(root, "yaml", "md");
  }

  /** Every file with one of {@code extensions} under {@code root}; empty if {@code root} doesn't exist. */
  public static Map<String, String> files(String root, String... extensions) {
    try {
      var out = new HashMap<String, String>();
      var resolver = new PathMatchingResourcePatternResolver();
      var rootResource = resolver.getResource("classpath:" + root);
      if (!rootResource.exists()) return out;
      String base = rootResource.getURL().toString();
      for (String ext : extensions) {
        for (var r : resolver.getResources("classpath*:" + root + "**/*." + ext)) {
          String url = r.getURL().toString();
          String rel = url.startsWith(base) ? url.substring(base.length())
              : url.substring(url.lastIndexOf(root) + root.length());
          out.put(rel, r.getContentAsString(StandardCharsets.UTF_8));
        }
      }
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
