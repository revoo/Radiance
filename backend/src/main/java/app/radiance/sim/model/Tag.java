package app.radiance.sim.model;

/**
 * A stored constraint tag on an actor (SPEC §5.5): {@code budget_tight}, {@code injured:knee}.
 *
 * @param until world time it lapses; null until an event removes it
 */
public record Tag(String tag, long since, Long until, String sourceEventId) {

  public boolean activeAt(long t) {
    return since <= t && (until == null || t < until);
  }

  /** The tag's name without its parameter: {@code injured} for {@code injured:knee}. */
  public static String base(String tag) {
    int i = tag.indexOf(':');
    return i < 0 ? tag : tag.substring(0, i);
  }
}
