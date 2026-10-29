package app.radiance.sim.model;

import app.radiance.content.Vocab.Gender;
import app.radiance.content.Vocab.Observance;
import app.radiance.content.Vocab.StatusKind;
import app.radiance.content.Vocab.Temperament;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A resident or R, as the engine sees them: the persona (fixed) plus continuous and daily state
 * (mutable). R has no persona: his traits, routine and temperament are empty or neutral.
 */
public final class Person {

  public record Politics(double economic, double social, double interest) {}

  public record Bias(String target, double strength, String style, boolean strong) {}

  public record Temper(Temperament preset, double baseline, double goodGain, double badGain, double floor,
      double ceiling) {
    public static final Temper NEUTRAL = new Temper(Temperament.TYPICAL, 0, 1, 1, -100, 100);
  }

  /** @param weekday online intensity per hour 0–23, 0–1 */
  public record Routine(String schedule, double[] weekday, double[] weekend, double postsPerDay, int attentionBudget) {
    public double[] curve(boolean weekendDay) {
      return weekendDay ? weekend : weekday;
    }
  }

  public final String id;
  public final String handle;
  public final String name;
  public final boolean user;
  public final Gender gender;
  public final String birthDate;
  public final List<String> circles;
  public final Map<String, Double> traits;
  public final Set<String> quirks;
  public final Map<String, Double> interests;
  public final Politics politics;
  public final List<Bias> biases;
  public final Map<String, Double> taste;
  public final Temper temper;
  public final Routine routine;
  public final String faith;
  public final Observance observance;
  public final boolean hasKids;
  public final boolean pinned;

  // identity the stance model reads for biases (SPEC §5.2)
  public String heritage;
  public String livesIn;
  public String occupation;

  // actor row: changes only through arc events
  public StatusKind status;
  public String partnerId;

  // continuous state (SPEC §5.2), at stateAt
  public double mood;
  public double energy;
  public double stress;
  public Double partnerSatisfaction;
  public long stateAt;
  public Map<String, Double> traitOffsets;
  public String location;

  // daily state
  public String interactionsDay;
  public int interactionsToday;
  public Long lastFeedCheck;

  public boolean dirtyState;
  public boolean dirtyActor;

  public Person(String id, String handle, String name, boolean user, Gender gender, String birthDate,
      List<String> circles, Map<String, Double> traits, Set<String> quirks, Map<String, Double> interests,
      Politics politics, List<Bias> biases, Map<String, Double> taste, Temper temper, Routine routine, String faith,
      Observance observance, boolean hasKids, boolean pinned) {
    this.id = id;
    this.handle = handle;
    this.name = name;
    this.user = user;
    this.gender = gender;
    this.birthDate = birthDate;
    this.circles = List.copyOf(circles);
    this.traits = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(traits));
    this.quirks = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(quirks));
    this.interests = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(interests));
    this.politics = politics;
    this.biases = List.copyOf(biases);
    this.taste = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(taste));
    this.temper = temper;
    this.routine = routine;
    this.faith = faith;
    this.observance = observance;
    this.hasKids = hasKids;
    this.pinned = pinned;
  }

  public Person copy() {
    var p = new Person(id, handle, name, user, gender, birthDate, circles, traits, quirks, interests, politics, biases,
        taste, temper, routine, faith, observance, hasKids, pinned);
    p.heritage = heritage;
    p.livesIn = livesIn;
    p.occupation = occupation;
    p.status = status;
    p.partnerId = partnerId;
    p.mood = mood;
    p.energy = energy;
    p.stress = stress;
    p.partnerSatisfaction = partnerSatisfaction;
    p.stateAt = stateAt;
    p.traitOffsets = new HashMap<>(traitOffsets);
    p.location = location;
    p.interactionsDay = interactionsDay;
    p.interactionsToday = interactionsToday;
    p.lastFeedCheck = lastFeedCheck;
    return p;
  }

  /** A trait with its drift offset, 0–100; 50 when unknown (R, or a trait genesis didn't set). */
  public double trait(String name) {
    double base = traits.getOrDefault(name, 50.0);
    return Math.max(0, Math.min(100, base + traitOffsets.getOrDefault(name, 0.0)));
  }

  /** {@code sports_diehard:white_sox} matches {@code sports_diehard}. */
  public boolean hasQuirk(String id) {
    for (String q : quirks) {
      if (q.equals(id) || q.startsWith(id + ":")) return true;
    }
    return false;
  }

  public double interest(String topic) {
    double best = interests.getOrDefault(topic, 0.0);
    if (topic.equals("sports")) {
      for (var e : interests.entrySet()) if (e.getKey().startsWith("sports:")) best = Math.max(best, e.getValue());
    }
    return best;
  }

  /** Whole years on {@code day} (YYYY-MM-DD); 31 when unknown. */
  public int age(String day) {
    if (birthDate == null) return 31;
    return java.time.Period.between(java.time.LocalDate.parse(birthDate), java.time.LocalDate.parse(day)).getYears();
  }

  public boolean attached() {
    return status == StatusKind.DATING || status == StatusKind.MARRIED;
  }

  /** Interactions so far on {@code day}; the counter resets with the world-day. */
  public int interactionsOn(String day) {
    return day.equals(interactionsDay) ? interactionsToday : 0;
  }

  public void countInteraction(String day) {
    if (!day.equals(interactionsDay)) {
      interactionsDay = day;
      interactionsToday = 0;
    }
    interactionsToday++;
    dirtyState = true;
  }

  @Override
  public String toString() {
    return "@" + handle;
  }
}
