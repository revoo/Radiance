package app.radiance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.radiance.RadianceApplication;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The {@code prod} profile on a real server (ADR 0018), behind what Caddy sends: the SPA is public and
 * routes survive a refresh, the API isn't and never falls back to the page, cookies are Secure over
 * forwarded HTTPS, anonymous requests hold no session, and real credentials are required.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"prod", "test"})
class ProdProfileTests {

  static final String PASSWORD = "correct horse battery staple";

  @DynamicPropertySource
  static void credentials(DynamicPropertyRegistry r) {
    r.add("radiance.security.username", () -> "sam");
    r.add("radiance.security.password-bcrypt", () -> new BCryptPasswordEncoder().encode(PASSWORD));
  }

  @LocalServerPort int port;
  @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
  final HttpClient http = HttpClient.newHttpClient();

  HttpResponse<String> get(String path, String... headers) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (headers.length > 0) b.headers(headers);
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> postForm(String path, String form, String cookie, String token, String proto) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/x-www-form-urlencoded").header("Cookie", cookie).header("X-XSRF-TOKEN", token)
        .header("X-Forwarded-Proto", proto).POST(HttpRequest.BodyPublishers.ofString(form));
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  static String cookie(HttpResponse<?> r, String name) {
    return r.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
  }

  static String value(String setCookie) {
    return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';') < 0 ? setCookie.length() : setCookie.indexOf(';'));
  }

  @Test
  void theSpaIsPublicAndItsRoutesSurviveARefresh() throws Exception {
    for (String page : List.of("/", "/messages", "/messages/abc123", "/people/sarah.kim", "/posts/01ABC")) {
      var r = get(page);
      assertThat(r.statusCode()).as(page).isEqualTo(200);
      assertThat(r.body()).as(page).contains("the SPA");
      assertThat(r.headers().firstValue("Cache-Control").orElse("")).as("the page is never stale").contains("no-cache");
    }
    var asset = get("/assets/app-abc123.js");
    assertThat(asset.statusCode()).isEqualTo(200);
    assertThat(asset.headers().firstValue("Cache-Control").orElse("")).contains("immutable");
    assertThat(get("/assets/missing-999.js").statusCode()).as("a missing asset isn't the page").isEqualTo(404);
    assertThat(get("/favicon.ico").statusCode()).as("a missing file isn't the page").isEqualTo(404);
    assertThat(get("/.env").statusCode()).as("bots probing for files get a 404").isEqualTo(404);
  }

  @Test
  void theApiNeverFallsBackToThePage() throws Exception {
    for (String path : List.of("/api/me", "/api/nope", "/api/director/pacer", "/actuator/metrics", "/internal/batch/nightly")) {
      var r = get(path);
      assertThat(r.statusCode()).as(path).isEqualTo(401);
      assertThat(r.body()).as(path).doesNotContain("the SPA");
    }
    assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
  }

  /** The SPA's paths are open to anyone, so no controller may live outside /api. */
  @Test
  void everyControllerIsBehindTheLogin() {
    var outside = new ArrayList<String>();
    mappings.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().forEach(p -> {
      if (!p.startsWith("/api/") && !p.equals("/error")) outside.add(p);
    }));
    assertThat(outside).isEmpty();
  }

  /** Regression: every anonymous request used to create a session held for 30 days. */
  @Test
  void anonymousRequestsHoldNoSession() throws Exception {
    assertThat(cookie(get("/api/me"), "JSESSIONID")).isNull();
    assertThat(cookie(get("/api/nope"), "JSESSIONID")).isNull();
    assertThat(cookie(get("/"), "JSESSIONID")).isNull();
  }

  @Test
  void overForwardedHttpsTheCookiesAreSecure() throws Exception {
    var first = get("/api/me", "X-Forwarded-Proto", "https");
    String xsrf = cookie(first, "XSRF-TOKEN");
    assertThat(xsrf).as("the SPA still gets its CSRF cookie").isNotNull().contains("Secure");
    assertThat(xsrf).doesNotContain("HttpOnly");
    String token = value(xsrf);

    String form = "username=sam&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
    var login = postForm("/api/auth/login", form, "XSRF-TOKEN=" + token, token, "https");
    assertThat(login.statusCode()).isEqualTo(200);
    String session = cookie(login, "JSESSIONID");
    assertThat(session).contains("Secure").contains("HttpOnly").containsIgnoringCase("SameSite=Lax");

    var me = get("/api/me", "Cookie", "JSESSIONID=" + value(session), "X-Forwarded-Proto", "https");
    assertThat(me.statusCode()).isEqualTo(200);
    assertThat(me.body()).contains("\"sam\"");
  }

  @Test
  void theDevLoginDoesNotWorkInProd() throws Exception {
    var first = get("/api/me");
    String token = value(cookie(first, "XSRF-TOKEN"));
    var r = postForm("/api/auth/login", "username=" + LoginCredentials.DEV_USERNAME + "&password=" + LoginCredentials.DEV_PASSWORD,
        "XSRF-TOKEN=" + token, token, "https");
    assertThat(r.statusCode()).isEqualTo(401);
  }

  @Test
  void prodWontStartWithoutRealCredentials() {
    assertThatThrownBy(() -> {
      try (var ctx = new SpringApplicationBuilder(RadianceApplication.class).web(WebApplicationType.SERVLET)
          .profiles("prod", "test").properties("server.port=0", "radiance.security.username=", "radiance.security.password-bcrypt=")
          .run()) {
        ctx.isActive();
      }
    }).hasStackTraceContaining("RADIANCE_USERNAME and RADIANCE_PASSWORD_BCRYPT must be set");
  }
}
