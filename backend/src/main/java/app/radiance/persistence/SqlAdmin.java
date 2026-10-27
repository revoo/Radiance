package app.radiance.persistence;

/**
 * The unfenced write path, for the few writes that happen outside the writer lane and before (or in
 * order to get) the writer lease: migrations, the placeholder world row, and the lease's own
 * conditional updates (ADR 0012). Domain code never uses it.
 */
public interface SqlAdmin {

  /** Runs the unit atomically, from any thread, without the lease fence. */
  void execute(UnitOfWork uow);
}
