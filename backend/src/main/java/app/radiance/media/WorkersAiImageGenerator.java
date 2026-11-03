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
  private static final String SIZE = "1024";

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final URI endpoint;
  private final String token;
  /** FLUX.2 takes a multipart form, even for just a prompt. */
  private final boolean multipart;

  public WorkersAiImageGenerator(URI base, String accountId, String model, String token) {
    this.endpoint = URI.create(base + "/client/v4/accounts/" + accountId + "/ai/run/" + model);
    this.token = token;
    this.multipart = model.contains("/flux-2-");
  }

  @Override
  public Generated generate(String prompt, long seed) {
    // no seed: the model answers 400 to one
    String boundary = "radiance" + Long.toHexString(prompt.hashCode() & 0xffffffffL);
    String body = multipart ? form(boundary, Map.of("prompt", prompt, "width", SIZE, "height", SIZE))
        : Json.write(Map.of("prompt", prompt, "steps", STEPS));
    var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token)
        .header("Content-Type", multipart ? "multipart/form-data; boundary=" + boundary : "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
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

  private static String form(String boundary, Map<String, String> fields) {
    var out = new StringBuilder();
    fields.forEach((k, v) -> out.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(k)
        .append("\"\r\n\r\n").append(v).append("\r\n"));
    return out.append("--").append(boundary).append("--\r\n").toString();
  }

  @Override
  public boolean real() {
    return true;
  }

}
