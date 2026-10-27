package app.radiance.world;

/**
 * The world row, minus the writer-lease columns, which only the lease touches. Times are world time
 * except {@code createdAt} at bootstrap, when the offset is zero and the two agree.
 */
public record WorldRow(
    String id,
    String name,
    String networkName,
    String timezone,
    long seed,
    long committedUntil,
    long scriptedUntil,
    long timeOffsetMs,
    boolean paused,
    double tension,
    Long lastSessionAt,
    String settingsJson,
    long createdAt) {}
