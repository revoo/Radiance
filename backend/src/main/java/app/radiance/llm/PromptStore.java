package app.radiance.llm;

import app.radiance.kernel.Json;
import app.radiance.llm.provider.ProviderRequest;
import app.radiance.llm.provider.ProviderResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps prompts and responses for the prompt inspector and the bake-off when
 * {@code radiance.llm.store-prompts} is on (SPEC §5.16). Files, not rows: prompts are large and D1
 * rows are capped at 1 MB. {@code llm_call.prompt_ref} points at the file.
 */
public interface PromptStore {

  /** Saves one attempt and returns its reference, or null if nothing was stored. */
  String store(String callId, long wallMs, ProviderRequest request, ProviderResponse response);

  PromptStore NONE = (id, ms, req, resp) -> null;

  /** {@code <dir>/<yyyy-MM-dd>/<callId>.json}; images are replaced by their size. */
  final class Files implements PromptStore {
    private static final Logger log = LoggerFactory.getLogger(Files.class);
    private final Path dir;

    public Files(Path dir) {
      this.dir = dir;
    }

    /** Removes the day folders older than {@code keepDays}: prompts are large, and the server's disk isn't. Returns how many went. */
    public static int prune(Path dir, long wallMs, int keepDays) {
      var oldest = Instant.ofEpochMilli(wallMs).atZone(ZoneOffset.UTC).toLocalDate().minusDays(keepDays);
      if (!java.nio.file.Files.isDirectory(dir)) return 0;
      int gone = 0;
      try (var days = java.nio.file.Files.list(dir)) {
        for (Path day : days.toList()) {
          try {
            if (!java.time.LocalDate.parse(day.getFileName().toString()).isBefore(oldest)) continue;
          } catch (java.time.format.DateTimeParseException notADay) {
            continue;
          }
          try (var files = java.nio.file.Files.walk(day)) {
            for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(p);
          }
          gone++;
        }
      } catch (IOException e) {
        log.warn("Could not prune stored prompts: {}", e.toString());
      }
      return gone;
    }

    @Override
    public String store(String callId, long wallMs, ProviderRequest req, ProviderResponse resp) {
      var node = Json.MAPPER.createObjectNode();
      node.put("callId", callId).put("purpose", req.purpose().key()).put("alias", req.alias())
          .put("model", req.model().id()).put("maxOutputTokens", req.maxOutputTokens());
      var prefix = node.putArray("prefix");
      req.prefix().forEach(b -> prefix.add(b.text()));
      var content = node.putArray("content");
      for (Part p : req.content()) {
        content.add(p instanceof Part.Text t ? t.text()
            : "[image " + ((Part.Image) p).mime() + ", " + ((Part.Image) p).base64().length() + " b64 chars]");
      }
      node.set("schema", req.schema());
      node.put("outcome", resp.outcome().name()).put("response", resp.text()).put("error", resp.error());
      String day = Instant.ofEpochMilli(wallMs).atZone(ZoneOffset.UTC).toLocalDate().toString();
      Path file = dir.resolve(day).resolve(callId + ".json");
      try {
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, node.toPrettyString());
        return day + "/" + callId + ".json";
      } catch (IOException e) {
        log.warn("Could not store prompt {}: {}", callId, e.toString());
        return null;
      }
    }
  }
}
