package app.radiance.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.RadianceApplication;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

/**
 * Regression, found by the image's smoke test: an open live stream counts as an active request, so graceful
 * shutdown waited out its whole timeout for it, and `docker stop` killed the app before it released the
 * writer lease. A deploy with the app open in a tab must still stop promptly. On a real server.
 */
class ShutdownTests {

  @Test
  void anOpenLiveStreamDoesntHoldUpShutdown() throws Exception {
    var ctx = (ServletWebServerApplicationContext) new SpringApplicationBuilder(RadianceApplication.class)
        .web(WebApplicationType.SERVLET).profiles("offline", "test").properties("server.port=0").run();
    boolean closed = false;
    try {
      int port = ctx.getWebServer().getPort();
      var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
      var http = HttpClient.newBuilder().cookieHandler(cookies).build();
      String base = "http://localhost:" + port;
      http.send(HttpRequest.newBuilder(URI.create(base + "/api/me")).build(), HttpResponse.BodyHandlers.discarding());
      String token = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN")).findFirst()
          .orElseThrow().getValue();
      var login = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("X-XSRF-TOKEN", token)
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString("username=" + LoginCredentials.DEV_USERNAME + "&password=" + LoginCredentials.DEV_PASSWORD))
          .build(), HttpResponse.BodyHandlers.discarding());
      assertThat(login.statusCode()).isEqualTo(200);

      // a tab left open: the stream is connected and nobody's closing it
      var stream = http.send(HttpRequest.newBuilder(URI.create(base + "/api/stream")).build(), HttpResponse.BodyHandlers.ofLines());
      assertThat(stream.statusCode()).isEqualTo(200);
      assertThat(stream.body().iterator().next()).contains("hello");

      long t0 = System.nanoTime();
      ctx.close();
      closed = true;
      long ms = (System.nanoTime() - t0) / 1_000_000;
      assertThat(ms).as("shutdown with a stream open").isLessThan(10_000);
    } finally {
      if (!closed) ctx.close();
    }
  }

  /**
   * Regression, seen on the server: a stream whose login had ended got an async dispatch when it closed, and the
   * security chain turned that into an Access Denied stack trace in the log. The request was authorized when it began.
   */
  @Test
  @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
  void aStreamThatOutlivesItsLoginClosesQuietly(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    var ctx = (ServletWebServerApplicationContext) new SpringApplicationBuilder(RadianceApplication.class)
        .web(WebApplicationType.SERVLET).profiles("offline", "test").properties("server.port=0").run();
    boolean closed = false;
    try {
      int port = ctx.getWebServer().getPort();
      var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
      var http = HttpClient.newBuilder().cookieHandler(cookies).build();
      String base = "http://localhost:" + port;
      http.send(HttpRequest.newBuilder(URI.create(base + "/api/me")).build(), HttpResponse.BodyHandlers.discarding());
      String token = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN")).findFirst()
          .orElseThrow().getValue();
      http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("X-XSRF-TOKEN", token)
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString("username=" + LoginCredentials.DEV_USERNAME + "&password=" + LoginCredentials.DEV_PASSWORD))
          .build(), HttpResponse.BodyHandlers.discarding());
      var stream = http.send(HttpRequest.newBuilder(URI.create(base + "/api/stream")).build(), HttpResponse.BodyHandlers.ofLines());
      assertThat(stream.body().iterator().next()).contains("hello");

      String fresh = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN")).findFirst()
          .orElseThrow().getValue();
      var logout = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/logout")).header("X-XSRF-TOKEN", fresh)
          .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
      assertThat(logout.statusCode() / 100).isEqualTo(2);

      ctx.close();
      closed = true;
      assertThat(output.toString()).doesNotContain("AuthorizationDeniedException");
    } finally {
      if (!closed) ctx.close();
    }
  }
}
