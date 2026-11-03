package app.radiance.media;

import java.util.Optional;
import java.util.Set;

/** What an upload really is, from its first bytes: a client can claim anything, and SVGs can carry scripts. */
public final class ImageTypes {

  public static final Set<String> UPLOADABLE = Set.of("image/jpeg", "image/png", "image/webp");

  private ImageTypes() {}

  public static Optional<String> sniff(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return Optional.of("image/jpeg");
    if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D && b[5] == 0x0A
        && b[6] == 0x1A && b[7] == 0x0A) {
      return Optional.of("image/png");
    }
    if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B'
        && b[11] == 'P') {
      return Optional.of("image/webp");
    }
    return Optional.empty();
  }
}
