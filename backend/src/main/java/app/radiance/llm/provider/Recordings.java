package app.radiance.llm.provider;

import app.radiance.kernel.Json;
import app.radiance.llm.Block;
import app.radiance.llm.Outcome;
import app.radiance.llm.Part;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Record and replay (SPEC §5.10): real responses stored by request hash, so prompt and parsing tests
 * use real model output without spending money. The hash covers everything that shapes the answer —
 * model id, purpose, prefix, content, schema and output limit — and nothing that doesn't (the alias
 * name, timeouts).
 */
public final class Recordings {

  private Recordings() {}

  public static String key(ProviderRequest r) {
    var node = Json.MAPPER.createObjectNode();
    node.put("model", r.model().id());
    node.put("purpose", r.purpose().key());
    var prefix = node.putArray("prefix");
    for (Block b : r.prefix()) prefix.add(b.text());
    var content = node.putArray("content");
    for (Part p : r.content()) {
      switch (p) {
        case Part.Text t -> content.add(t.text());
        case Part.Image i -> content.add("image:" + i.mime() + ":" + sha256(i.base64()));
      }
    }
    node.set("schema", r.schema());
    node.put("maxOutputTokens", r.maxOutputTokens());
    return sha256(node.toString());
  }

  static String sha256(String s) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Passes calls through and saves every answer the provider actually gave. */
  public static LlmProvider recording(LlmProvider delegate, Path dir) {
    return request -> {
      var response = delegate.complete(request);
      if (response.outcome() != Outcome.ERROR && response.outcome() != Outcome.TIMEOUT
          && response.outcome() != Outcome.RATE_LIMITED) {
        try {
          Files.createDirectories(dir);
          Files.writeString(dir.resolve(key(request) + ".json"), Json.write(response));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      return response;
    };
  }

  /** Serves recorded answers; an unrecorded request is an ERROR that names its key. */
  public static LlmProvider replay(Path dir) {
    return request -> {
      Path file = dir.resolve(key(request) + ".json");
      if (!Files.exists(file)) {
        return ProviderResponse.failure(Outcome.ERROR, request.model().id(),
            "no recording " + file.getFileName() + " for " + request.purpose().key());
      }
      try {
        return Json.read(Files.readString(file), ProviderResponse.class);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
  }
}
