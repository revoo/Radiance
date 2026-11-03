package app.radiance.media;

import app.radiance.kernel.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/** Cloudflare Workers AI (SPEC §5.13): a model that returns a base64 image. No SDK, as with the LLM adapters (ADR 0008). */
public final class WorkersAiImageGenerator implements ImageGenerator {

  private static final int STEPS = 4;

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final URI endpoint;
  private final String token;

  public WorkersAiImageGenerator(URI base, String accountId, String model, String token) {
    this.endpoint = URI.create(base + "/client/v4/accounts/" + accountId + "/ai/run/" + model);
    this.token = token;
  }

  @Override
  public Generated generate(String prompt, long seed) {
    // the model's seed is a 32-bit integer or less
    long small = Math.floorMod(seed, 2_147_483_647L);
    var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("prompt", prompt, "seed", small, "steps", STEPS))))
        .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Workers AI call failed: " + e, e);
    }
    int status = response.statusCode();
    // 429: rate limited or out of the day's neurons. 402: an account past its allowance.
    if (status == 429 || status == 402) throw new QuotaExceeded("Workers AI answered " + status);
    if (status != 200) {
      if (response.body() != null && response.body().toLowerCase(java.util.Locale.ROOT).contains("neurons")) {
        throw new QuotaExceeded("Workers AI is out of neurons");
      }
      throw new IllegalStateException("Workers AI answered " + status);
    }
    String image = Json.tree(response.body()).path("result").path("image").asString("");
    if (image.isEmpty()) throw new IllegalStateException("Workers AI returned no image");
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(image);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Workers AI returned bad base64", e);
    }
    return new Generated(bytes, ImageTypes.sniff(bytes).orElseThrow(() -> new IllegalStateException("not an image")));
  }

  @Override
  public boolean real() {
    return true;
  }

}
