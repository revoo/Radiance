package app.radiance.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Media on the local disk. Writes land whole: a crash can't leave half an image behind. */
public final class LocalFileMediaStore implements MediaStore {

  private final Path root;

  public LocalFileMediaStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  Path path(String key) {
    var path = root.resolve(key).normalize();
    if (!path.startsWith(root)) throw new IllegalArgumentException("bad media key");
    return path;
  }

  @Override
  public void put(String key, byte[] bytes) throws IOException {
    var path = path(key);
    Files.createDirectories(path.getParent());
    var temp = Files.createTempFile(path.getParent(), "upload", ".tmp");
    try {
      Files.write(temp, bytes);
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Override
  public Optional<byte[]> get(String key) throws IOException {
    try {
      return Optional.of(Files.readAllBytes(path(key)));
    } catch (NoSuchFileException e) {
      return Optional.empty();
    }
  }
}
