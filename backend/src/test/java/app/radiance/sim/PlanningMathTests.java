package app.radiance.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.radiance.content.Vocab.Category;
import app.radiance.content.Vocab.Gender;
import app.radiance.content.Vocab.Observance;
import app.radiance.content.Vocab.StatusKind;
import app.radiance.content.Vocab.Temperament;
import app.radiance.kernel.Fidelity;
import app.radiance.sim.model.Person;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PlanningMathTests {

  static final long DAY = 86_400_000L;

  @Test
  void calibrationHitsTheMixAndRoughlyEachPostingRate() {
    var raw = new TreeMap<String, EnumMap<Category, Double>>();
    var rates = new TreeMap<String, Double>();
    for (int i = 0; i < 10; i++) {
      var row = new EnumMap<Category, Double>(Category.class);
      row.put(Category.DAILY, 0.5 + i * 0.1);
      row.put(Category.NEWS, i % 2 == 0 ? 2.0 : 0.1);
      row.put(Category.LIFE, 0.05);
      row.put(Category.DRAMA, 0.01 * i);
      raw.put("r" + i, row);
      rates.put("r" + i, i == 0 ? 0.05 : 1.0 + i * 0.3);
    }
    var mix = Map.of("DAILY", 0.5, "NEWS", 0.3, "LIFE", 0.15, "DRAMA", 0.05, "HOBBY", 0.1);
    var s = Rates.calibrate(raw, rates, mix);
    double total = rates.values().stream().mapToDouble(Double::doubleValue).sum();
    var col = new EnumMap<Category, Double>(Category.class);
    raw.forEach((id, row) -> row.forEach((c, v) -> col.merge(c, v * s.get(c.name()) * s.get("actor:" + id), Double::sum)));
    // HOBBY has nothing eligible, so its 10% goes to the others in proportion
    assertThat(col.get(Category.DAILY) / total).isCloseTo(0.5 / 1.0, within(0.01));
    assertThat(col.get(Category.NEWS) / total).isCloseTo(0.3, within(0.01));
    assertThat(s.get("HOBBY")).isZero();
    double r0 = 0;
    for (var e : raw.get("r0").entrySet()) r0 += e.getValue() * s.get(e.getKey().name()) * s.get("actor:r0");
    assertThat(r0).isCloseTo(0.05, within(0.02)); // the lurker stays a lurker
  }

  @Test
  void backfillPartitionsAGapByAge() {
    var k = Knobs.defaults();
    long now = 100 * DAY;
    var slices = Fidelities.backfill(now - 40 * DAY, now, k);
    assertThat(slices.getFirst().fidelity()).isNull(); // the TIME_SKIP beyond max-gap
    assertThat(slices.getFirst().end()).isEqualTo(now - 30 * DAY);
    assertThat(slices.getLast().fidelity()).isEqualTo(Fidelity.FULL);
    assertThat(slices.stream().filter(x -> x.fidelity() == Fidelity.FULL).count()).isEqualTo(48);
    for (int i = 1; i < slices.size(); i++) assertThat(slices.get(i).start()).isEqualTo(slices.get(i - 1).end());
    assertThat(Fidelities.forIdle(now - 3 * DAY, now, k)).isEqualTo(Fidelity.FULL);
    assertThat(Fidelities.forIdle(now - 10 * DAY, now, k)).isEqualTo(Fidelity.REDUCED);
    assertThat(Fidelities.forIdle(now - 30 * DAY, now, k)).isEqualTo(Fidelity.SUMMARY);
    assertThat(Fidelities.forIdle(now - 61 * DAY, now, k)).isNull();
  }

  @Test
  void theFaithfulNeverFlirtBackAndNoOneFlirtsWithTheirOwnGender() {
    var k = Knobs.defaults();
    var her = person("her", Gender.F, StatusKind.MARRIED, 85);
    var him = person("him", Gender.M, StatusKind.SINGLE, 50);
    assertThat(Stances.flirt(her, him, 90, 0.8, k)).isEqualTo(Double.NEGATIVE_INFINITY);
    assertThat(Stances.flirt(person("x", Gender.F, StatusKind.SINGLE, 50), person("y", Gender.F, StatusKind.SINGLE, 50),
        90, 0.8, k)).isEqualTo(Double.NEGATIVE_INFINITY);
    double low = Stances.flirt(person("l", Gender.F, StatusKind.MARRIED, 15), him, 60, 0.3, k);
    double mid = Stances.flirt(person("m", Gender.F, StatusKind.MARRIED, 60), him, 60, 0.3, k);
    assertThat(low).isGreaterThan(mid);
    assertThat(Storyteller.factor("STEADY", 7, 0, 100, 1)).isGreaterThanOrEqualTo(0.2);
    assertThat(Storyteller.factor("STEADY", 7, 0, 0, 1)).isGreaterThan(1);
  }

  @Test
  void biasTargetsMatchIdentity() {
    var p = person("p", Gender.M, StatusKind.SINGLE, 50);
    p.heritage = "Indian-American";
    assertThat(Stances.matches(p, "Indians", "2026-10-28")).isTrue();
    assertThat(Stances.matches(p, "Black people", "2026-10-28")).isFalse();
    p.livesIn = "Chicago (Lincoln Square)";
    assertThat(Stances.matches(p, "city people", "2026-10-28")).isTrue();
  }

  static Person person(String id, Gender g, StatusKind status, double faithfulness) {
    var traits = new HashMap<String, Double>();
    traits.put("faithfulness", faithfulness);
    var p = new Person(id, id, id, false, g, "1993-05-05", List.of(), traits, java.util.Set.of(), Map.of(),
        new Person.Politics(0, 0, 0.3), List.of(), Map.of(),
        new Person.Temper(Temperament.TYPICAL, 10, 1, 0.4, -30, 100), null, null, Observance.NONE, false, false);
    p.traitOffsets = new HashMap<>();
    p.status = status;
    return p;
  }
}
