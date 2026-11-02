package app.radiance.backup;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where backups go (ADR 0019), from the environment: {@code R2_ACCESS_KEY_ID}, {@code R2_SECRET_ACCESS_KEY},
 * {@code R2_BACKUP_BUCKET} (or {@code R2_BUCKET}), and {@code CF_ACCOUNT_ID} or a full {@code R2_ENDPOINT}.
 */
public record R2Settings(String endpoint, String accountId, String accessKey, String secretKey, String bucket, String region) {

  static final String ENV_NAMES = "R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BACKUP_BUCKET, CF_ACCOUNT_ID";

  public static R2Settings from(Map<String, String> env) {
    String bucket = env.getOrDefault("R2_BACKUP_BUCKET", "");
    if (bucket.isBlank()) bucket = env.getOrDefault("R2_BUCKET", "");
    String region = env.getOrDefault("R2_REGION", "");
    return new R2Settings(env.getOrDefault("R2_ENDPOINT", ""), env.getOrDefault("CF_ACCOUNT_ID", ""),
        env.getOrDefault("R2_ACCESS_KEY_ID", ""), env.getOrDefault("R2_SECRET_ACCESS_KEY", ""), bucket,
        region.isBlank() ? "auto" : region);
  }

  /** The names of whatever's missing; empty when backups can leave the server. */
  public List<String> missing() {
    var out = new ArrayList<String>();
    if (accessKey.isBlank()) out.add("R2_ACCESS_KEY_ID");
    if (secretKey.isBlank()) out.add("R2_SECRET_ACCESS_KEY");
    if (bucket.isBlank()) out.add("R2_BACKUP_BUCKET");
    if (endpoint.isBlank() && accountId.isBlank()) out.add("CF_ACCOUNT_ID");
    return out;
  }

  public Optional<S3Client> client() {
    if (!missing().isEmpty()) return Optional.empty();
    String url = endpoint.isBlank() ? "https://" + accountId + ".r2.cloudflarestorage.com" : endpoint;
    return Optional.of(new S3Client(new S3Client.Settings(URI.create(url), bucket, accessKey, secretKey, region)));
  }
}
