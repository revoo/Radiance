package app.radiance.media;

import app.radiance.kernel.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * A Cloudflare partner model (ADR 0023): the model goes in the body, it's paid from AI Gateway credits rather than free neurons,
 * and the answer is a short-lived link to the picture rather than the picture.
 */
public final class PartnerImageGenerator implements ImageGenerator {

  private static final long MAX_SEED = 4_294_967_295L;

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
      .followRedirects(HttpClient.Redirect.NORMAL).build();
  private final URI endpoint;
  private final String model;
  private final String token;

  public PartnerImageGenerator(URI base, String accountId, String model, String token) {
    this.endpoint = URI.create(base + "/client/v4/accounts/" + accountId + "/ai/run");
    this.model = model;
    this.token = token;
  }

  @Override
  public Generated generate(String prompt, long seed) {
    var input = Map.of("prompt", prompt, "resolution", "1k", "aspect_ratio", "1:1", "seed", Math.floorMod(seed, MAX_SEED));
    var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(120))
        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("model", model, "input", input)))).build();
    String body = send(request, HttpResponse.BodyHandlers.ofString()).body();
    var result = Json.tree(body).path("result");
    String image = result.path("result").path("image").asString(result.path("image").asString(""));
    if (image.isEmpty()) throw new IllegalStateException("Partner model returned no image");
    byte[] bytes;
    if (image.startsWith("http")) {
      bytes = send(HttpRequest.newBuilder(URI.create(image)).timeout(Duration.ofSeconds(60)).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray()).body();
    } else {
      try {
        bytes = Base64.getDecoder().decode(image);
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException("Partner model returned bad base64", e);
      }
    }
    return new Generated(bytes, ImageTypes.sniff(bytes).orElseThrow(() -> new IllegalStateException("not an image")));
  }

  private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    HttpResponse<T> response;
    try {
      response = http.send(request, handler);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Partner model call failed: " + e, e);
    }
    int status = response.statusCode();
    // 402: the credits ran out. 429: rate limited.
    if (status == 402 || status == 429) throw new QuotaExceeded("Partner model answered " + status);
    if (status != 200) throw new IllegalStateException("Partner model answered " + status);
    return response;
  }

  @Override
  public boolean real() {
    return true;
  }
}
