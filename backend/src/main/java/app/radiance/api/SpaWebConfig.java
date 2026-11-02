package app.radiance.api;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * The app serves the built SPA itself (ADR 0018), from {@code classpath:/static/}: hashed assets are
 * cached for good, and any other path that isn't a file name gets {@code index.html}, so client-side
 * routes survive a refresh. API paths never fall back to the page. With no built SPA on the classpath
 * (development, where Vite serves it), everything here is simply a 404.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class SpaWebConfig implements WebMvcConfigurer {

  private static final String STATIC = "classpath:/static/";
  private static final Pattern FILE = Pattern.compile(
      "\\.(js|mjs|css|map|html?|ico|png|jpe?g|gif|svg|webp|avif|woff2?|ttf|txt|xml|json|webmanifest|php|env)$",
      Pattern.CASE_INSENSITIVE);

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/assets/**")
        .addResourceLocations(STATIC + "assets/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    registry.addResourceHandler("/**")
        .addResourceLocations(STATIC)
        .setCacheControl(CacheControl.noCache())
        .resourceChain(true)
        .addResolver(new PathResourceResolver() {
          @Override
          protected Resource getResource(String path, Resource location) throws IOException {
            var file = location.createRelative(path);
            if (!path.isEmpty() && file.exists() && file.isReadable()) return file;
            if (!isClientRoute(path)) return null;
            var page = location.createRelative("index.html");
            return page.exists() && page.isReadable() ? page : null;
          }
        });
  }

  /** A page of the SPA: not the API or the server's own paths, and not a missing file like {@code x.js}. */
  static boolean isClientRoute(String path) {
    if (path.equals("api") || path.startsWith("api/") || path.startsWith("actuator") || path.startsWith("internal/")
        || path.startsWith("assets/")) {
      return false;
    }
    // handles have dots (/people/sarah.kim): only a real file extension makes it a missing file
    return !FILE.matcher(path).find();
  }
}
