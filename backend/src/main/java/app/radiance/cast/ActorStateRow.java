package app.radiance.cast;

/** Continuous state, written at most once per world-hour per actor unless an event shocks it. */
public record ActorStateRow(
    String actorId,
    double mood,
    double energy,
    double stress,
    Double partnerSatisfaction,
    String traitOffsetsJson,
    String location,
    long interactionsToday,
    Long lastFeedCheckAt,
    long updatedAt) {}
