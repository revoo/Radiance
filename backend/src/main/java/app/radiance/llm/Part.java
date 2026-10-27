package app.radiance.llm;

/** Variable request content after the cached prefix: text, or an image for vision purposes. */
public sealed interface Part {

  record Text(String text) implements Part {}

  /** A base64-encoded image, e.g. {@code image/jpeg}. */
  record Image(String mime, String base64) implements Part {}

  static Part text(String text) {
    return new Text(text);
  }
}
