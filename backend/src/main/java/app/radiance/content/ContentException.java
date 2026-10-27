package app.radiance.content;

import java.util.List;

/** Invalid content. The message lists every problem found, each with its file and path. */
public final class ContentException extends RuntimeException {

  private final List<String> problems;

  public ContentException(List<String> problems) {
    super("Invalid content (" + problems.size() + " problem" + (problems.size() == 1 ? "" : "s")
        + "):\n  - " + String.join("\n  - ", problems));
    this.problems = List.copyOf(problems);
  }

  public List<String> problems() {
    return problems;
  }
}
