package app.radiance.sim;

import app.radiance.content.Defs.EventDef;
import app.radiance.content.Vocab.Category;
import app.radiance.sim.model.Person;
import app.radiance.world.Calendar;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Event rates (SPEC §5.4): a def's base rate times its modifiers for one resident, and the event-mix
 * calibration that scales categories so the cast's posts land on {@code storyteller.event-mix} while
 * each resident's volume still follows their posting rate.
 *
 * <p>Modifier semantics (the spec names the keys, not the math): a trait {@code m} multiplies by
 * m^((trait − 50) / 50), so 50 is neutral and 100 gives ×m; a state {@code m} by m^value; an interest by
 * 1 + (m − 1)·weight; {@code quirk.*}, {@code weekend}, {@code has_kids}, {@code has_partner},
 * {@code observance.*}, {@code season.*} and {@code temperament.*} by m when they hold.
 */
public final class Rates {

  private Rates() {}

  public static double modifiers(EventDef def, Person p, LocalDate d, double tension) {
    double x = 1;
    for (var e : def.modifiers().entrySet()) {
      String k = e.getKey();
      double m = e.getValue();
      if (k.startsWith("quirk.")) {
        if (p.hasQuirk(k.substring(6))) x *= m;
      } else if (k.startsWith("interest.")) {
        x *= 1 + (m - 1) * p.interest(k.substring(9));
      } else if (k.startsWith("observance.")) {
        if (p.observance != null && p.observance.name().equalsIgnoreCase(k.substring(11))) x *= m;
      } else if (k.startsWith("season.")) {
        if (Calendar.season(d).equals(k.substring(7))) x *= m;
      } else if (k.startsWith("temperament.")) {
        if (p.temper.preset().name().equalsIgnoreCase(k.substring(12))) x *= m;
      } else {
        switch (k) {
          case "weekend" -> x *= Presence.weekend(d) ? m : 1;
          case "has_kids" -> x *= p.hasKids ? m : 1;
          case "has_partner" -> x *= p.attached() ? m : 1;
          case "mood" -> x *= Math.pow(m, p.mood);
          case "stress" -> x *= Math.pow(m, p.stress);
          case "energy" -> x *= Math.pow(m, p.energy);
          case "partner_satisfaction" -> x *= p.partnerSatisfaction == null ? 1 : Math.pow(m, p.partnerSatisfaction);
          case "tension" -> x *= Math.pow(m, tension / 50 - 1);
          default -> x *= Math.pow(m, (p.trait(k) - 50) / 50);
        }
      }
    }
    return x;
  }

  /**
   * Category scales for the coming week (SPEC §5.4), by iterative proportional fitting over the
   * resident × category matrix of expected posts: rows sum to each resident's posting rate, columns to
   * the mix's share of the total. A category with nothing eligible gives its share to the others.
   *
   * @param raw resident id → category → expected posts per day at scale 1 (tension factor 1)
   * @param rates resident id → posts per day
   * @return the scale per category, and per resident ({@code actor:<id>} keys)
   */
  public static Map<String, Double> calibrate(Map<String, EnumMap<Category, Double>> raw, Map<String, Double> rates,
      Map<String, Double> mix) {
    var cats = List.of(Category.DAILY, Category.NEWS, Category.LIFE, Category.HOBBY, Category.FAMILY, Category.SOCIAL,
        Category.DRAMA);
    var available = new EnumMap<Category, Double>(Category.class);
    for (var c : cats) {
      double sum = 0;
      for (var row : raw.values()) sum += row.getOrDefault(c, 0.0);
      if (sum > 0) available.put(c, mix.getOrDefault(c.name(), 0.0));
    }
    double shareTotal = available.values().stream().mapToDouble(Double::doubleValue).sum();
    double total = rates.values().stream().mapToDouble(Double::doubleValue).sum();
    var col = new EnumMap<Category, Double>(Category.class);
    cats.forEach(c -> col.put(c, 1.0));
    var row = new TreeMap<String, Double>();
    raw.keySet().forEach(k -> row.put(k, 1.0));
    for (int iter = 0; iter < 50; iter++) {
      for (var e : raw.entrySet()) {
        double s = 0;
        for (var c : cats) s += e.getValue().getOrDefault(c, 0.0) * col.get(c);
        double want = rates.getOrDefault(e.getKey(), 0.0);
        row.put(e.getKey(), s > 0 ? want / s : 0);
      }
      for (var c : cats) {
        double s = 0;
        for (var e : raw.entrySet()) s += e.getValue().getOrDefault(c, 0.0) * row.get(e.getKey());
        double want = shareTotal > 0 ? total * available.getOrDefault(c, 0.0) / shareTotal : 0;
        col.put(c, s > 0 ? want / s : 0);
      }
    }
    var out = new TreeMap<String, Double>();
    col.forEach((c, v) -> out.put(c.name(), v));
    row.forEach((k, v) -> out.put("actor:" + k, v));
    return out;
  }
}
