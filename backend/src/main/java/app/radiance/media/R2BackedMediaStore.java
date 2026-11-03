package app.radiance.media;

import app.radiance.backup.S3Client;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;

/**
 * The local disk, kept in R2 (ADR 0021): every write goes to both, and reads come from the disk, filling in from R2
 * when a file isn't there, as after a restore onto a new server. So serving a photo never waits on R2.
 */
public final class R2BackedMediaStore implements MediaStore {

  static final String PREFIX = "media/";

  private final LocalFileMediaStore local;
  private final S3Client remote;

  public R2BackedMediaStore(LocalFileMediaStore local, S3Client remote) {
    this.local = local;
    this.remote = remote;
  }

  @Override
  public void put(String key, byte[] bytes) throws IOException, InterruptedException {
    local.put(key, bytes);
    remote.put(PREFIX + key, local.path(key));
  }

  @Override
  public Optional<byte[]> get(String key) throws IOException, InterruptedException {
    var here = local.get(key);
    if (here.isPresent()) return here;
    if (remote.head(PREFIX + key).isEmpty()) return Optional.empty();
    var path = local.path(key);
    Files.createDirectories(path.getParent());
    remote.get(PREFIX + key, path);
    return local.get(key);
  }
}
