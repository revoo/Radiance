package app.radiance.persistence;

/** A unit of work was refused because this process doesn't hold the current writer lease. */
public final class LeaseLostException extends RuntimeException {

  public LeaseLostException(String message) {
    super(message);
  }

  public LeaseLostException(String message, Throwable cause) {
    super(message, cause);
  }
}
