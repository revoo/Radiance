package app.radiance.world.genesis;

import app.radiance.kernel.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** {@code world-seed.json}: pretty, stable field order, written atomically so a crash never leaves half a file. */
public final class SeedFiles {

  private SeedFiles() {}

  public static String toJson(WorldSeed seed) {
    return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(seed) + "\n";
  }

  public static void write(Path file, WorldSeed seed) {
    try {
      var parent = file.toAbsolutePath().getParent();
      Files.createDirectories(parent);
      var tmp = parent.resolve(file.getFileName() + ".tmp");
      Files.writeString(tmp, toJson(seed));
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static WorldSeed read(Path file) {
    try {
      var seed = Json.read(Files.readString(file), WorldSeed.class);
      if (seed.version() != WorldSeed.VERSION) {
        throw new IllegalArgumentException("world-seed.json version " + seed.version() + " is not " + WorldSeed.VERSION);
      }
      return seed;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
