package app.radiance.content;

import java.time.Duration;
import java.util.regex.Pattern;

/** Content-file durations: {@code 250ms}, {@code 2s}, {@code 4m}, {@code 6h}, {@code 30d}. */
public final class Durations {

  private static final Pattern P = Pattern.compile("(\\d+)(ms|s|m|h|d)");

  private Durations() {}

  public static Duration parse(String text) {
    var m = P.matcher(text == null ? "" : text.strip());
    if (!m.matches()) throw new IllegalArgumentException("not a duration: '" + text + "'");
    long n = Long.parseLong(m.group(1));
    return switch (m.group(2)) {
      case "ms" -> Duration.ofMillis(n);
      case "s" -> Duration.ofSeconds(n);
      case "m" -> Duration.ofMinutes(n);
      case "h" -> Duration.ofHours(n);
      default -> Duration.ofDays(n);
    };
  }
}
