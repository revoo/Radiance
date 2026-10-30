package app.radiance.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * The SPA's session flow against a real server, the way a browser does it (SPEC §5.17): no test
 * shortcuts for authentication or CSRF, since MockMvc doesn't show the CSRF cookie the server sends.
 * Regression: logging out must end the session, so the next read asks for a login again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"offline", "test"})
class SpaSessionTests {

  @LocalServerPort int port;

  final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
  final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

  HttpResponse<String> send(String method, String path, String form, boolean withToken) throws Exception {
    var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (withToken) req.header("X-XSRF-TOKEN", xsrf());
    if (form != null) req.header("Content-Type", "application/x-www-form-urlencoded");
    req.method(method, form == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(form));
    return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
  }

  String xsrf() {
    return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
        .map(HttpCookie::getValue).findFirst().orElse(null);
  }

  @Test
  void loginWithTheCsrfCookieThenLogoutEndsTheSession() throws Exception {
    assertThat(send("GET", "/api/me", null, false).statusCode()).isEqualTo(401);
    assertThat(xsrf()).as("any response hands out the CSRF cookie").isNotNull();

    String form = "username=" + URLEncoder.encode(LoginCredentials.DEV_USERNAME, StandardCharsets.UTF_8)
        + "&password=" + URLEncoder.encode(LoginCredentials.DEV_PASSWORD, StandardCharsets.UTF_8);
    var login = send("POST", "/api/auth/login", form, true);
    assertThat(login.statusCode()).isEqualTo(200);
    assertThat(login.body()).contains("\"username\"");
    assertThat(send("GET", "/api/me", null, false).statusCode()).isEqualTo(200);

    // writes without the token are refused even when signed in
    assertThat(send("POST", "/api/notifications/read", null, false).statusCode()).isEqualTo(403);

    var logout = send("POST", "/api/auth/logout", null, true);
    assertThat(logout.statusCode()).isEqualTo(204);
    assertThat(send("GET", "/api/me", null, false).statusCode()).as("logged out").isEqualTo(401);
  }

  @Test
  void aWrongPasswordIsAProblemNotARedirect() throws Exception {
    send("GET", "/api/me", null, false);
    var r = send("POST", "/api/auth/login", "username=r&password=nope", true);
    assertThat(r.statusCode()).isEqualTo(401);
    assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
  }
}
