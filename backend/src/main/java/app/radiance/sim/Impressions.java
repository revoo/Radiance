package app.radiance.sim;

import app.radiance.content.ContentLibrary;
import app.radiance.content.Rules.Deltas;
import app.radiance.sim.model.Edge;
import app.radiance.sim.model.Meters;
import app.radiance.sim.model.Person;
import app.radiance.sim.model.SimWorld;
import java.util.List;
import java.util.Map;

/**
 * How residents come to see R (SPEC §5.9): every appraised thing he posts moves each viewer's
 * impression — Δaffinity = sensitivity · (taste · features) · novelty · saturation, through their
 * temperament and mood; attraction only across genders; respect from intellect and effort — and
 * impression tags flip with hysteresis: only after crossing a threshold by a margin and staying there
 * for two world-days.
 *
 * <p>Running perceptions live next to the pending tags as {@code ~humor}, {@code ~pretentious} and
 * {@code ~annoying} (×1000), so they persist with the edge.
 */
public final class Impressions {

  private Impressions() {}

  static final long HYSTERESIS = 2 * 86_400_000L;
  static final double MARGIN = 0.05;
  static final List<String> TAGS = List.of("fan", "finds_funny", "finds_pretentious", "annoyed_by", "crush",
      "thinks_cool", "rival", "protective");

  /** One viewer sees one of R's posts or comments. */
  public static void see(SimWorld w, Person viewer, Person user, Map<String, Double> features, double saturation,
      ContentLibrary lib, Knobs k, long t) {
    var e = w.edge(viewer.id, user.id);
    Meters.settle(e, viewer, lib.relationships(), k.affinityHalfLifeDays(), t);
    double match = 0, weight = 0;
    for (var f : features.entrySet()) {
      double taste = viewer.taste.getOrDefault(f.getKey(), 0.0);
      match += taste * (f.getValue() - 0.3);
      weight += Math.abs(taste);
    }
    if (weight > 0) match /= weight;
    double novelty = 1.0;
    double dAff = k.impressionSensitivity() * match * 6 * novelty * saturation;
    double dAtt = 0;
    if (viewer.gender != user.gender) {
      dAtt = k.crushPropensity() * (features.getOrDefault("flirtiness", 0.0) * 0.6 + features.getOrDefault("warmth", 0.0) * 0.3
          + features.getOrDefault("humor", 0.0) * 0.3 - 0.25) * (1 + e.chemistry) * 2 * (viewer.attached() ? 0.4 : 1);
    }
    double dRespect = (features.getOrDefault("intellect", 0.0) + features.getOrDefault("effort", 0.0) - 0.9) * 2
        - features.getOrDefault("pretentiousness", 0.0) * Math.max(0, -viewer.taste.getOrDefault("pretentiousness", 0.0)) * 3;
    Meters.apply(e, new Deltas(dAff, 0, dAtt, 1.0, dRespect), viewer, lib.temperament(), false, t);
    perceive(e, "~humor", features.getOrDefault("humor", 0.0) * (0.5 + Math.max(0, viewer.taste.getOrDefault("humor", 0.0))));
    perceive(e, "~pretentious", features.getOrDefault("pretentiousness", 0.0)
        * (0.6 + Math.max(0, -viewer.taste.getOrDefault("pretentiousness", 0.0))));
    perceive(e, "~annoying", Math.max(0, -match) + features.getOrDefault("edginess", 0.0) * 0.3);
    retag(e, viewer, t);
  }

  /** Exponential moving average of a perception, stored ×1000. */
  private static void perceive(Edge e, String key, double value) {
    double old = e.pending.getOrDefault(key, 0L) / 1000.0;
    e.pending.put(key, Math.round((old * 0.8 + value * 0.2) * 1000));
    e.dirty = true;
  }

  static double score(Edge e, String key) {
    return e.pending.getOrDefault(key, 0L) / 1000.0;
  }

  /** How far past its threshold a tag is (positive: qualifies), in units of 0–1. */
  static double over(Edge e, Person viewer, String tag) {
    return switch (tag) {
      case "fan" -> Math.min(e.affinity - 50, e.familiarity - 30) / 100;
      case "finds_funny" -> score(e, "~humor") - 0.35;
      case "finds_pretentious" -> score(e, "~pretentious") - 0.35;
      case "annoyed_by" -> Math.min(score(e, "~annoying") - 0.3, (-10 - e.affinity) / 100);
      case "crush" -> (e.attraction - 55) / 100;
      case "thinks_cool" -> Math.min(e.respect - 30, e.affinity - 25) / 100;
      case "rival" -> Math.min(-30 - e.affinity, e.respect) / 100;
      case "protective" -> Math.min(e.trust - 60, e.affinity - 55) / 100;
      default -> -1;
    };
  }

  /** Flips a tag only after it has held past the margin for {@link #HYSTERESIS}. */
  static void retag(Edge e, Person viewer, long t) {
    for (String tag : TAGS) {
      double o = over(e, viewer, tag);
      boolean has = e.tags.contains(tag);
      boolean wants = has ? o > -MARGIN : o > MARGIN;
      String key = (has ? "-" : "+") + tag;
      if (wants == has) {
        if (e.pending.remove(key) != null) e.dirty = true;
        continue;
      }
      Long since = e.pending.get(key);
      if (since == null) {
        e.pending.put(key, t);
        e.dirty = true;
      } else if (t - since >= HYSTERESIS) {
        if (has) e.tags.remove(tag);
        else e.tags.add(tag);
        e.pending.remove(key);
        e.dirty = true;
      }
    }
  }
}
