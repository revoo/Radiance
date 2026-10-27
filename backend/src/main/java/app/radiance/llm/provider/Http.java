package app.radiance.llm.provider;

import app.radiance.llm.Outcome;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** What the HTTP adapters share: the client, the per-request timeout and status mapping. */
final class Http {

  /** A finished exchange, read before the connection is released. */
  record Raw(int status, HttpHeaders headers, String body) {}

  private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)s\"");

  private Http() {}

  static RestClient.Builder clientBuilder() {
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    return RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(http));
  }

  static RestClient.RequestHeadersSpec.ExchangeFunction<Raw> raw() {
    return (req, res) -> new Raw(res.getStatusCode().value(), res.getHeaders(),
        new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
  }

  /**
   * Runs the exchange on a virtual thread and gives up after {@code timeout}, so a hung provider costs
   * a TIMEOUT, not a stuck render.
   */
  static ProviderResponse withTimeout(
      Duration timeout, String modelId, java.util.concurrent.Callable<ProviderResponse> call) {
    try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
      var future = exec.submit(call);
      try {
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        future.cancel(true);
        return ProviderResponse.failure(Outcome.TIMEOUT, modelId, "no answer within " + timeout);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        future.cancel(true);
        return ProviderResponse.failure(Outcome.ERROR, modelId, "interrupted");
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        for (Throwable t = cause; t != null; t = t.getCause()) {
          if (t instanceof HttpTimeoutException || t instanceof java.net.SocketTimeoutException) {
            return ProviderResponse.failure(Outcome.TIMEOUT, modelId, t.toString());
          }
        }
        return ProviderResponse.failure(Outcome.ERROR, modelId, String.valueOf(cause));
      }
    }
  }

  /** Non-2xx statuses, normalized. 4xx other than 408 and 429 are our mistake and not retried. */
  static ProviderResponse failure(Raw raw, String modelId, Function<String, Outcome> bodyOverride) {
    Outcome override = bodyOverride.apply(raw.body());
    if (override != null) return ProviderResponse.failure(override, modelId, snippet(raw));
    return switch (raw.status()) {
      case 429 -> new ProviderResponse(Outcome.RATE_LIMITED, null, ProviderResponse.TokenCounts.ZERO,
          modelId, 0, retryAfterMs(raw), snippet(raw));
      case 408, 504 -> ProviderResponse.failure(Outcome.TIMEOUT, modelId, snippet(raw));
      default -> raw.status() >= 400 && raw.status() < 500
          ? ProviderResponse.failure(Outcome.ERROR, modelId, ProviderResponse.CLIENT_ERROR + snippet(raw))
          : ProviderResponse.failure(Outcome.ERROR, modelId, snippet(raw));
    };
  }

  static Long retryAfterMs(Raw raw) {
    String header = raw.headers().getFirst("Retry-After");
    if (header != null) {
      try {
        return (long) (Double.parseDouble(header.strip()) * 1000);
      } catch (NumberFormatException ignored) {
        // an HTTP date; fall through to the body
      }
    }
    var m = RETRY_DELAY.matcher(raw.body() == null ? "" : raw.body());
    return m.find() ? Long.parseLong(m.group(1)) * 1000 : null;
  }

  static String snippet(Raw raw) {
    String body = raw.body() == null ? "" : raw.body();
    return "HTTP " + raw.status() + ": " + (body.length() > 500 ? body.substring(0, 500) : body);
  }

  static String join(String baseUrl, String path) {
    return baseUrl.endsWith("/") ? baseUrl + path.substring(1) : baseUrl + path;
  }
}
