package app.radiance.sim;

import app.radiance.content.Vocab.Stance;
import java.util.Map;

/**
 * Scripted R personas for the tuning harness (SPEC §10): how often he posts and comments, what his
 * content reads like to an appraisal (tone features 0–1), and the stances his comments take.
 */
public enum Persona {
  WARM(1.2, 0.5, 6, 0.6, features(0.4, 0.85, 0.4, 0.05, 0.1, 0.4, 0.85, 0.05, 0.05, 0.6, 0.15),
      Map.of(Stance.SUPPORT, 0.5, Stance.CONGRATS, 0.2, Stance.JOKE, 0.2, Stance.QUESTION, 0.1)),
  FUNNY(1.5, 0.4, 6, 0.6, features(0.9, 0.5, 0.4, 0.1, 0.1, 0.2, 0.6, 0.4, 0.05, 0.6, 0.2),
      Map.of(Stance.JOKE, 0.7, Stance.SUPPORT, 0.2, Stance.QUESTION, 0.1)),
  PRETENTIOUS(1.5, 0.3, 5, 0.3, features(0.15, 0.2, 0.7, 0.9, 0.7, 0.1, 0.4, 0.2, 0.6, 0.7, 0.05),
      Map.of(Stance.ADVICE, 0.4, Stance.DISAGREE, 0.3, Stance.QUESTION, 0.2, Stance.SELF_PIVOT, 0.1)),
  QUIET(0.15, 0.5, 1, 0.2, features(0.3, 0.6, 0.4, 0.05, 0.05, 0.3, 0.6, 0.05, 0.05, 0.4, 0.05),
      Map.of(Stance.SUPPORT, 0.8, Stance.JOKE, 0.2)),
  COMBATIVE(1.2, 0.2, 8, 0.5, features(0.3, 0.1, 0.5, 0.4, 0.3, 0.05, 0.2, 0.85, 0.5, 0.5, 0.05),
      Map.of(Stance.DISAGREE, 0.4, Stance.MOCK, 0.2, Stance.HOSTILE, 0.2, Stance.TROLL, 0.2));

  /** Posts per day, share of them that are photos, comments per day, chance he answers a comment. */
  public final double postsPerDay;
  public final double photoShare;
  public final int commentsPerDay;
  public final double replyRate;
  public final Map<String, Double> features;
  public final Map<Stance, Double> stances;

  Persona(double postsPerDay, double photoShare, int commentsPerDay, double replyRate, Map<String, Double> features,
      Map<Stance, Double> stances) {
    this.postsPerDay = postsPerDay;
    this.photoShare = photoShare;
    this.commentsPerDay = commentsPerDay;
    this.replyRate = replyRate;
    this.features = features;
    this.stances = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(stances));
  }

  static Map<String, Double> features(double humor, double warmth, double intellect, double pretentiousness,
      double bragging, double vulnerability, double positivity, double edginess, double preachiness, double effort,
      double flirtiness) {
    var m = new java.util.TreeMap<String, Double>();
    m.put("humor", humor);
    m.put("warmth", warmth);
    m.put("intellect", intellect);
    m.put("pretentiousness", pretentiousness);
    m.put("bragging", bragging);
    m.put("vulnerability", vulnerability);
    m.put("positivity", positivity);
    m.put("edginess", edginess);
    m.put("preachiness", preachiness);
    m.put("effort", effort);
    m.put("flirtiness", flirtiness);
    return java.util.Collections.unmodifiableMap(m);
  }

  /** {@code scripted:warm} or {@code warm}. */
  public static Persona parse(String s) {
    String v = s.startsWith("scripted:") ? s.substring(9) : s;
    return valueOf(v.toUpperCase());
  }
}
