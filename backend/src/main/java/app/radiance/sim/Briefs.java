package app.radiance.sim;

import app.radiance.sim.model.Item;

/** Reads the engagement-relevant extras of a {@link Brief} onto an {@link Item}. */
final class Briefs {

  private Briefs() {}

  static void decorate(Item item, Brief b) {
    item.valence = num(b.extra().get("valence"));
    item.lean = num(b.extra().get("lean"));
    item.tragic = Boolean.TRUE.equals(b.extra().get("tragic"));
  }

  private static double num(Object o) {
    return o instanceof Number n ? n.doubleValue() : 0;
  }
}
