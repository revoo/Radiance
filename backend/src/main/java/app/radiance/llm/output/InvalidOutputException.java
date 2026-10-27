package app.radiance.llm.output;

import java.util.List;

/** Model output that isn't JSON or doesn't match its schema. The message is fed to the repair call. */
public final class InvalidOutputException extends Exception {

  private final List<String> errors;

  public InvalidOutputException(List<String> errors) {
    super(String.join("; ", errors));
    this.errors = List.copyOf(errors);
  }

  public List<String> errors() {
    return errors;
  }
}
