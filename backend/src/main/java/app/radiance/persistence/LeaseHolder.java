package app.radiance.persistence;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** What this process believes about its writer lease. {@link WriterLease} sets it; commits read it. */
public final class LeaseHolder {

  /** The fencing token: our owner id and the epoch we acquired. */
  public record Fence(String owner, long epoch) {}

  private final AtomicReference<Fence> current = new AtomicReference<>();

  public Optional<Fence> current() {
    return Optional.ofNullable(current.get());
  }

  public boolean isHeld() {
    return current.get() != null;
  }

  void set(Fence fence) {
    current.set(fence);
  }

  /** Clears the lease only if it is still {@code fence}, so a newer acquisition isn't lost. */
  void clearIf(Fence fence) {
    current.compareAndSet(fence, null);
  }

  void clear() {
    current.set(null);
  }
}
