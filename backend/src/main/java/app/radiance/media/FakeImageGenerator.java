package app.radiance.media;

import java.nio.charset.StandardCharsets;

/**
 * A labeled placeholder for offline play and tests: an SVG, so it needs no fonts or imaging libraries on the server.
 * The seed picks its colors, so the same thing looks the same across posts.
 */
public final class FakeImageGenerator implements ImageGenerator {

  public static final String SVG = "image/svg+xml";
  private static final int LABEL = 90;

  @Override
  public Generated generate(String prompt, long seed) {
    int hue = (int) Math.floorMod(seed, 360L);
    String label = prompt.length() > LABEL ? prompt.substring(0, LABEL - 1).stripTrailing() + "…" : prompt;
    String svg = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 600" width="800" height="600" role="img">
        <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="hsl(%d 55%% 38%%)"/><stop offset="1" stop-color="hsl(%d 60%% 20%%)"/>
        </linearGradient></defs>
        <rect width="800" height="600" fill="url(#g)"/>
        <circle cx="620" cy="130" r="70" fill="hsl(%d 80%% 75%% / 0.35)"/>
        <path d="M0 470 L190 300 L340 430 L500 260 L800 480 L800 600 L0 600 Z" fill="hsl(%d 40%% 14%% / 0.65)"/>
        <text x="40" y="552" fill="white" fill-opacity="0.9" font-family="system-ui, sans-serif" font-size="22">%s</text>
        </svg>
        """.formatted(hue, (hue + 40) % 360, (hue + 180) % 360, hue, escape(label));
    return new Generated(svg.getBytes(StandardCharsets.UTF_8), SVG);
  }

  @Override
  public boolean real() {
    return false;
  }

  static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
  }
}
