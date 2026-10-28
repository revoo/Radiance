package app.radiance.sim;

import app.radiance.content.ContentLibrary;
import app.radiance.content.Vocab.Stance;
import app.radiance.kernel.RandomSource;
import app.radiance.sim.model.Edge;
import app.radiance.sim.model.Item;
import app.radiance.sim.model.Person;
import app.radiance.sim.model.SimWorld;
import app.radiance.social.ReactionRow;
import java.util.EnumMap;

/**
 * Stance and reaction kind (SPEC §5.8): a softmax over trait, relationship, sentiment and pattern
 * logits. Biases add to TROLL, MOCK and HOSTILE when the author belongs to their target; FLIRT follows
 * the faithfulness ladder (SPEC §5.2) and only ever pairs a man and a woman; {@code content.edge}
 * scales the ugly stances for everyone; META needs two prior clashes.
 */
public final class Stances {

  private Stances() {}

  static final double NEVER = Double.NEGATIVE_INFINITY;

  public static Stance comment(SimWorld w, Person viewer, Person author, Item item, ContentLibrary lib, Knobs k,
      String day, RandomSource rng) {
    return sample(logits(w, viewer, author, item, lib, k, day), rng);
  }

  static EnumMap<Stance, Double> logits(SimWorld w, Person b, Person a, Item i, ContentLibrary lib, Knobs k,
      String day) {
    var e = w.edgeOrNull(b.id, a.id);
    double aff = e == null ? 0 : e.affinity, att = e == null ? 0 : e.attraction;
    double chem = e == null ? 0 : e.chemistry;
    boolean news = "NEWS".equals(i.category);
    double align = news ? alignment(b, i) : 0;
    boolean bad = i.valence < -0.2 || i.tragic, good = i.valence > 0.2;
    var l = new EnumMap<Stance, Double>(Stance.class);
    l.put(Stance.SUPPORT, 0.3 + aff / 60 + (b.trait("agreeableness") - 50) / 60 + (bad ? 0.8 : 0) - (news ? 0.8 : 0));
    l.put(Stance.CONGRATS, good && i.importance >= 4 ? 1.6 + aff / 70 : NEVER);
    l.put(Stance.CONDOLENCE, bad && (i.importance >= 4 || i.tragic) ? 1.6 + (b.trait("agreeableness") - 50) / 80 : NEVER);
    l.put(Stance.JOKE, -0.3 + (b.trait("humor") - 50) / 35 - (bad && i.importance >= 5 ? 1.8 : 0));
    l.put(Stance.AGREE, news ? 0.8 * align * (0.6 + b.politics.interest()) + 0.2 : -0.6 + aff / 100);
    double rival = e != null && e.hasTag("debate_rival") ? 1.5 : 0;
    l.put(Stance.DISAGREE, news ? -1.0 * align * (0.4 + b.trait("argumentativeness") / 60) + rival - 0.3
        : -1.8 + (b.trait("argumentativeness") - 50) / 40 - aff / 70 + rival);
    double edge = k.edge() <= 0 ? NEVER : Math.log(k.edge());
    double bias = bias(b, a, day);
    double hate = Math.max(0, -aff);
    l.put(Stance.TROLL, -1.4 + (b.trait("argumentativeness") - 50) / 35 + hate / 40 + bias + edge);
    l.put(Stance.MOCK, -2.0 + hate / 30 + bias + (news && align < -0.4 ? 0.6 : 0) + edge);
    l.put(Stance.HOSTILE, -2.8 + hate / 22 + bias * 0.8 + (e != null && e.hasTag("feud") ? 2 : 0) + edge);
    l.put(Stance.FLIRT, flirt(b, a, att, chem, k));
    l.put(Stance.QUESTION, -0.9 + (b.trait("nosiness") - 50) / 50 + (good ? 0.3 : 0));
    l.put(Stance.ADVICE, -1.4 + (b.trait("conscientiousness") - 50) / 50 + (bad && !i.tragic ? 0.6 : 0));
    l.put(Stance.SELF_PIVOT, -2.2 + (b.trait("attention_seeking") - 50) / 30);
    l.put(Stance.HYPE, -1.2 + (b.trait("extraversion") - 50) / 50 + ("PHOTO".equals(i.kind) ? 0.5 : 0) + (good ? 0.4 : 0));
    l.put(Stance.META, i.drama >= 0.3 && w.clashes.getOrDefault(Edge.pair(b.id, a.id), 0) >= 2 ? -0.8 : NEVER);
    if (i.tragic) {
      l.compute(Stance.JOKE, (s, v) -> v - 2);
      l.compute(Stance.CONDOLENCE, (s, v) -> v == NEVER ? 1.6 : v + 0.8);
    }
    for (String q : b.quirks) {
      var def = lib.quirks().get(q.contains(":") ? q.substring(0, q.indexOf(':')) : q);
      if (def != null) def.mechanics().stanceBias().forEach((s, v) -> l.computeIfPresent(s, (x, old) -> old + v * 2));
    }
    for (String q : a.quirks) {
      var def = lib.quirks().get(q.contains(":") ? q.substring(0, q.indexOf(':')) : q);
      if (def == null) continue;
      for (var ab : def.mechanics().audienceBias().entrySet()) {
        String topic = ab.getKey().replace("on_", "").replace("_posts", "");
        if (i.topics.contains(topic)) ab.getValue().forEach((s, v) -> l.computeIfPresent(s, (x, old) -> old + v * 2));
      }
    }
    l.remove(Stance.REACT_ONLY);
    return l;
  }

  /** −1…1: how far the viewer agrees with a news take's lean (both on the same axes). */
  static double alignment(Person b, Item i) {
    double mine = (b.politics.economic() + b.politics.social()) / 2;
    return Math.max(-1, Math.min(1, mine * i.lean * 2.5));
  }

  /** The faithfulness ladder (SPEC §5.2), scaled by {@code romance.temptation}. */
  static double flirt(Person b, Person a, double attraction, double chemistry, Knobs k) {
    if (b.gender == a.gender) return NEVER;
    if (k.temptation() <= 0 && (b.attached() || a.attached())) return NEVER;
    double base = -1.5 + attraction / 20 + chemistry * 1.5 + (b.trait("flirtatiousness") - 50) / 30;
    if (b.attached()) {
      double f = b.trait("faithfulness");
      if (f >= 80) return NEVER;
      base += f >= 50 ? -1.4 : f >= 25 ? -0.3 : 0.4;
      double sat = b.partnerSatisfaction == null ? 0.7 : b.partnerSatisfaction;
      base += (0.7 - sat) * 2;
      base += Math.log(Math.max(0.01, k.temptation()));
    }
    return base;
  }

  /** How strongly {@code b}'s biases fire on {@code a} (SPEC §5.2). */
  static double bias(Person b, Person a, String day) {
    double out = 0;
    for (var bias : b.biases) {
      if (matches(a, bias.target(), day)) out += bias.strength() * (bias.strong() ? 3.0 : 0.8);
    }
    return out;
  }

  /** Whether {@code p} belongs to a bias target such as "Black people", "Cubs fans" or "boomers". */
  public static boolean matches(Person p, String target, String day) {
    if (p.user || target == null) return false;
    String t = target.toLowerCase();
    String heritage = p.heritage == null ? "" : p.heritage.toLowerCase();
    String faith = p.faith == null ? "" : p.faith.toLowerCase();
    String city = p.livesIn == null ? "" : p.livesIn.toLowerCase();
    String job = p.occupation == null ? "" : p.occupation.toLowerCase();
    int age = p.age(day);
    if (t.contains("black")) return heritage.contains("black") || heritage.contains("african");
    if (t.contains("indian")) return heritage.contains("indian") && !heritage.contains("native");
    if (t.contains("mexican") || t.contains("latino") || t.contains("hispanic")) {
      return heritage.contains("mexican") || heritage.contains("latin") || heritage.contains("puerto") || heritage.contains("hispanic");
    }
    if (t.contains("asian")) return heritage.contains("korean") || heritage.contains("chinese") || heritage.contains("asian")
        || heritage.contains("indian") || heritage.contains("filipin");
    if (t.contains("immigrant")) return heritage.contains("immigrant") || heritage.contains("bosnian") || heritage.contains("ukrainian")
        || heritage.contains("serbian");
    if (t.contains("jew")) return faith.contains("jew");
    if (t.contains("muslim")) return faith.contains("muslim") || faith.contains("islam");
    if (t.contains("christian") || t.contains("church")) {
      return faith.contains("catholic") || faith.contains("christian") || faith.contains("lutheran") || faith.contains("method")
          || faith.contains("baptist") || faith.contains("orthodox") || faith.contains("presbyter") || faith.contains("evangel");
    }
    if (t.contains("atheist") || t.contains("religious")) return t.contains("atheist") ? faith.isEmpty() || faith.contains("none")
        : !faith.isEmpty() && !faith.contains("none");
    if (t.contains("liberal") || t.contains("progressive") || t.contains("democrat") || t.contains("left")) {
      return p.politics.social() < -0.3 || p.politics.economic() < -0.4;
    }
    if (t.contains("conservative") || t.contains("republican") || t.contains("right")) {
      return p.politics.social() > 0.3 || p.politics.economic() > 0.4;
    }
    if (t.contains("city")) return city.contains("chicago");
    if (t.contains("suburb")) return !city.isEmpty() && !city.contains("chicago");
    if (t.contains("boomer")) return age >= 61;
    if (t.contains("millennial")) return age >= 30 && age <= 45;
    if (t.contains("gen z")) return age < 30;
    if (t.contains("women")) return p.gender == app.radiance.content.Vocab.Gender.F;
    if (t.equals("men")) return p.gender == app.radiance.content.Vocab.Gender.M;
    if (t.contains("cubs")) return p.hasQuirk("sports_diehard:cubs") || p.interest("sports:cubs") > 0.5;
    if (t.contains("sox")) return p.hasQuirk("sports_diehard:white_sox") || p.interest("sports:white_sox") > 0.5;
    if (t.contains("lawyer")) return job.contains("attorney") || job.contains("lawyer");
    if (t.contains("cop") || t.contains("police")) return job.contains("police") || job.contains("officer");
    if (t.contains("rich") || t.contains("finance")) return job.contains("mortgage") || job.contains("consult") || job.contains("attorney");
    if (t.contains("downstate")) return heritage.contains("downstate");
    return false;
  }

  static Stance sample(EnumMap<Stance, Double> logits, RandomSource rng) {
    double max = Double.NEGATIVE_INFINITY;
    for (double v : logits.values()) max = Math.max(max, v);
    double total = 0;
    var weights = new EnumMap<Stance, Double>(Stance.class);
    for (var e : logits.entrySet()) {
      double x = e.getValue() == NEVER ? 0 : Math.exp(e.getValue() - max);
      weights.put(e.getKey(), x);
      total += x;
    }
    double u = rng.nextDouble() * total;
    for (var e : weights.entrySet()) {
      u -= e.getValue();
      if (u < 0) return e.getKey();
    }
    return Stance.SUPPORT;
  }

  /** The reaction that goes with a stance, or a fresh one for a reaction-only engagement. */
  public static ReactionRow.Kind reaction(Stance s, Person b, Item i, double affinity, RandomSource rng) {
    if (s != null) {
      return switch (s) {
        case JOKE, MOCK, TROLL -> ReactionRow.Kind.HAHA;
        case DISAGREE, HOSTILE -> ReactionRow.Kind.ANGRY;
        case CONDOLENCE -> affinity > 40 ? ReactionRow.Kind.CARE : ReactionRow.Kind.SAD;
        case CONGRATS, HYPE -> ReactionRow.Kind.LOVE;
        case FLIRT -> ReactionRow.Kind.LOVE;
        default -> ReactionRow.Kind.LIKE;
      };
    }
    double u = rng.nextDouble();
    if (i.tragic || i.valence < -0.3) return affinity > 40 && u < 0.6 ? ReactionRow.Kind.CARE : ReactionRow.Kind.SAD;
    if ("NEWS".equals(i.category)) {
      double align = alignment(b, i);
      if (align < -0.3) return u < 0.5 ? ReactionRow.Kind.ANGRY : affinity < -20 ? ReactionRow.Kind.HAHA : ReactionRow.Kind.WOW;
      return ReactionRow.Kind.LIKE;
    }
    if (i.valence > 0.3 && i.importance >= 5) return affinity > 30 || u < 0.4 ? ReactionRow.Kind.LOVE : ReactionRow.Kind.WOW;
    if (u < (b.trait("humor") - 30) / 300) return ReactionRow.Kind.HAHA;
    if ("PHOTO".equals(i.kind) && affinity > 45 && u < 0.5) return ReactionRow.Kind.LOVE;
    return ReactionRow.Kind.LIKE;
  }

  /** Ugly stances, for the feel report's 5–12% band. */
  public static boolean ugly(String stance) {
    return "TROLL".equals(stance) || "MOCK".equals(stance) || "HOSTILE".equals(stance);
  }
}
