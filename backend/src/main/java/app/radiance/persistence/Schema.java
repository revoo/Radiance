package app.radiance.persistence;

/** Facts about the schema that code outside SQL needs. */
public final class Schema {

  /** V1 has a single world (SPEC §4.1); its row id is fixed. */
  public static final String WORLD_ID = "world";

  /**
   * The fencing statement that starts every unit of work (SPEC §4.2): it writes NULL into a NOT NULL
   * column, aborting the whole unit, unless this process still holds the current lease epoch.
   */
  static final String FENCE_SQL =
      "INSERT INTO lease_fence (id, ok) VALUES (1, (SELECT 1 FROM world WHERE id = ? AND"
          + " lease_owner = ? AND lease_epoch = ?)) ON CONFLICT (id) DO UPDATE SET ok = excluded.ok";

  /** Appears in the database error when {@link #FENCE_SQL} rejects a unit. */
  static final String FENCE_VIOLATION = "lease_fence.ok";

  private Schema() {}
}
