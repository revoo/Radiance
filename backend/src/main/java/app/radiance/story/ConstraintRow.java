package app.radiance.story;

/**
 * A constraint tag an event put on an actor (SPEC §5.5), such as {@code budget_tight} or
 * {@code injured:knee}. Tags from an arc's current phase are derived from the arc, not stored.
 *
 * @param until world time the tag lapses; null until an event removes it
 */
public record ConstraintRow(String actorId, String tag, long since, Long until, String sourceEventId) {}
