package app.radiance.world.genesis;

import app.radiance.content.Vocab.EntityKind;
import app.radiance.content.Vocab.Gender;
import app.radiance.content.Vocab.Observance;
import app.radiance.content.Vocab.StatusKind;
import app.radiance.content.Vocab.Temperament;
import app.radiance.kernel.Visibility;
import java.util.List;
import java.util.Map;

/**
 * Everything genesis decides, as {@code data/world-seed.json} (SPEC §6.4): R reads and edits it, then
 * imports it. People are keyed by handle and things by readable keys — no database ids — so the file
 * is hand-editable. The skeleton fills the structural fields; flesh-out fills the prose fields
 * ({@code bio} … {@code offNetwork}, relationship {@code backstory}, entity descriptions).
 */
public record WorldSeed(
    int version,
    long seed,
    String referenceDate,
    List<Resident> residents,
    List<Relationship> relationships,
    List<Arc> arcs,
    List<Entity> entities) {

  public static final int VERSION = 1;

  public record Resident(
      String handle,
      String name,
      String displayName,
      Gender gender,
      String birthDate,
      String heritage,
      String faithTradition,
      Observance observance,
      String livesIn,
      String occupation,
      String employer,
      StatusKind status,
      String partner,
      List<String> circles,
      boolean pinned,
      String archetype,
      Map<String, Integer> traits,
      List<String> quirks,
      Map<String, Double> interests,
      Politics politics,
      List<Bias> biases,
      Map<String, Double> taste,
      TemperamentSeed temperament,
      Routine routine,
      InitialState state,
      List<OffNetworkPerson> offNetwork,
      // flesh-out
      String bio,
      String backstory,
      VoiceCard voice,
      List<String> values,
      List<String> fears,
      List<String> desires) {}

  /** Axes −1…1 (economic: left…right; social: progressive…conservative); interest 0–1. */
  public record Politics(double economic, double social, double interest, Map<String, Double> issues) {}

  /** @param style JOKES | DOG_WHISTLES | OPEN_CONTEMPT; strong = real prejudice (SPEC §5.2) */
  public record Bias(String target, double strength, String style, boolean strong) {}

  public record TemperamentSeed(
      Temperament preset, double baseline, double goodGain, double badGain, double floor, double ceiling) {}

  /**
   * @param weekday online intensity per hour 0–23, 0–1
   * @param postsPerDay lognormal across the cast: lurkers ≈ 0.05, oversharers ≈ 3
   * @param attentionBudget interactions per day
   */
  public record Routine(
      String chronotype, String schedule, String weekendPattern, List<Double> weekday,
      List<Double> weekend, double postsPerDay, int attentionBudget) {}

  public record InitialState(double mood, double energy, double stress, Double partnerSatisfaction) {}

  /** Family and others who aren't on the network; names are engine-chosen (SPEC §5.5). */
  public record OffNetworkPerson(String name, String relation, Integer age, String note) {}

  public record VoiceCard(
      String lengthBand, String emojiLevel, String casing, String punctuation, double typoRate,
      List<String> slang, List<String> catchphrases, List<String> signatureTopics,
      List<String> neverDoes, List<String> samplePosts, List<String> sampleComments) {}

  /** One direction of a tie: how {@code from} sees {@code to}. */
  public record Relationship(
      String from, String to, String kind, double affinity, double trust, double attraction,
      double familiarity, double respect, double chemistry, List<String> tags, boolean connected,
      String backstory) {}

  /**
   * @param roles role name → handle
   * @param knowers handles who know a PRIVATE/SECRET phase; empty for PUBLIC
   * @param daysIntoPhase how long the arc has been in {@code phase} on the reference date
   */
  public record Arc(
      String key, String defId, String phase, Visibility visibility, Map<String, String> roles,
      List<String> knowers, int daysIntoPhase, Map<String, Object> data) {}

  public record Entity(
      String key, String owner, EntityKind kind, String name, String visualDescription, long imageSeed,
      Map<String, Object> attributes, String status) {}
}
