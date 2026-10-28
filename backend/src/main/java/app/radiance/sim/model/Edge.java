package app.radiance.sim.model;

import app.radiance.graph.RelationshipRow.Kind;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * How {@code from} sees {@code to} (SPEC §5.3). Relaxation and decay are applied lazily by
 * {@link Meters#settle}, which brings the edge from {@link #settledAt} to any later time exactly, so a
 * backfill, an idle week and one hour of play all agree.
 */
public final class Edge {

  public final String from;
  public final String to;
  public Kind kind;
  public double affinity;
  public double trust;
  public double attraction;
  public double familiarity;
  public double respect;
  public final double chemistry;
  public final TreeSet<String> tags;
  /** Impression tags waiting out their hysteresis: tag → when it first qualified (SPEC §5.9). */
  public final TreeMap<String, Long> pending;
  public boolean connected;
  public String summary;
  public Long lastInteraction;
  public long settledAt;
  public boolean dirty;

  public Edge(String from, String to, Kind kind, double affinity, double trust, double attraction, double familiarity,
      double respect, double chemistry, TreeSet<String> tags, TreeMap<String, Long> pending, boolean connected,
      String summary, Long lastInteraction, long settledAt) {
    this.from = from;
    this.to = to;
    this.kind = kind;
    this.affinity = affinity;
    this.trust = trust;
    this.attraction = attraction;
    this.familiarity = familiarity;
    this.respect = respect;
    this.chemistry = chemistry;
    this.tags = tags;
    this.pending = pending;
    this.connected = connected;
    this.summary = summary;
    this.lastInteraction = lastInteraction;
    this.settledAt = settledAt;
  }

  /** A stranger edge: SPEC §5.3's defaults for a missing row. */
  public static Edge stranger(String from, String to, double chemistry, long now) {
    return new Edge(from, to, Kind.STRANGER, 0, 30, 0, 0, 0, chemistry, new TreeSet<>(), new TreeMap<>(), false, null,
        null, now);
  }

  public Edge copy() {
    var e = new Edge(from, to, kind, affinity, trust, attraction, familiarity, respect, chemistry, new TreeSet<>(tags),
        new TreeMap<>(pending), connected, summary, lastInteraction, settledAt);
    return e;
  }

  /** {@code debate_rival} matches {@code debate_rival:economics}. */
  public boolean hasTag(String tag) {
    for (String t : tags) if (t.equals(tag) || t.startsWith(tag + ":")) return true;
    return false;
  }

  public double get(String dimension) {
    return switch (dimension) {
      case "affinity" -> affinity;
      case "trust" -> trust;
      case "attraction" -> attraction;
      case "familiarity" -> familiarity;
      case "respect" -> respect;
      case "chemistry" -> chemistry * 100;
      default -> throw new IllegalArgumentException("unknown dimension " + dimension);
    };
  }

  public static String key(String from, String to) {
    return from + "|" + to;
  }

  public static String pair(String a, String b) {
    return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
  }
}
