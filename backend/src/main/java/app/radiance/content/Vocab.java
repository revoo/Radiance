package app.radiance.content;

import java.util.Set;

/** The fixed vocabularies content files are checked against (SPEC §5.2–§5.8). */
public final class Vocab {

  private Vocab() {}

  /** The event mix's categories, plus DMs to R, which sit outside the mix (SPEC §5.4). */
  public enum Category { DAILY, NEWS, LIFE, HOBBY, FAMILY, SOCIAL, DRAMA, DM_USER }

  /** What an event or arc phase posts. */
  public enum PostOutput { STATUS, PHOTO, LINK, LIFE_EVENT, CHECKIN, DM, NONE }

  /** How an arc beat surfaces (SPEC §5.4 forced beats, A.1). */
  public enum BeatKind { STATUS, PHOTO, LINK, LIFE_EVENT, CHECKIN, REACTIONS, DECLINE_PLANS, MENTION, MEMORY }

  /** Comment and reply stances (SPEC §5.8). */
  public enum Stance {
    SUPPORT, CONGRATS, CONDOLENCE, JOKE, AGREE, DISAGREE, TROLL, MOCK, HOSTILE, FLIRT, QUESTION,
    ADVICE, SELF_PIVOT, HYPE, META, REACT_ONLY
  }

  public enum EntityKind { CAR, HOME, PET, PROJECT, PLACE, BUSINESS, OBJECT }

  public enum Gender { M, F }

  public enum Observance { NONE, NOMINAL, PRACTICING, DEVOUT }

  public enum StatusKind { SINGLE, DATING, MARRIED, DIVORCED, WIDOWED }

  public enum Temperament { GROUCH, TYPICAL, SWEETHEART }

  /** Traits, 0–100 (SPEC §5.2): the Big Five plus the social-media dimensions. */
  public static final Set<String> TRAITS = Set.of(
      "openness", "conscientiousness", "extraversion", "agreeableness", "neuroticism",
      "argumentativeness", "attention_seeking", "humor", "nosiness", "sincerity", "flirtatiousness",
      "impulsivity", "optimism", "politics_interest", "faithfulness");

  /** Continuous state an event can move (SPEC §5.2). */
  public static final Set<String> STATE = Set.of("mood", "energy", "stress", "partner_satisfaction");

  /** The topic vocabulary shared with the headline classifier (SPEC §5.2); {@code sports:<team>} too. */
  public static final Set<String> TOPICS = Set.of(
      "politics", "economy", "tech", "cars", "home_improvement", "fitness", "food", "parenting",
      "pets", "music", "movies_tv", "gaming", "fashion", "faith", "outdoors", "travel", "true_crime",
      "science", "health", "local", "celebrity", "personal_finance");

  public static final Set<String> SPORTS_TEAMS = Set.of(
      "bears", "cubs", "white_sox", "bulls", "blackhawks", "fire", "sky", "red_stars", "illini");

  /** Roster circles (SPEC §6.2). */
  public static final Set<String> CIRCLES = Set.of(
      "lakeshore", "prairie-path", "kinzie", "uiuc", "berwyn-block", "softball", "family", "online");

  /** Relationship pattern tags (SPEC §5.3); parameterized ones take {@code :<topic>}. */
  public static final Set<String> RELATIONSHIP_TAGS = Set.of(
      "debate_rival", "frenemy", "crush", "protective_of", "avoiding", "hate_follows", "wingman",
      "teases_about", "suspects", "best_friend", "feud");

  /** Calendar triggers defs can name in {@code when.calendar} (SPEC §5.4, §6.1). */
  public static final Set<String> CALENDAR = Set.of(
      "birthday", "new_years_day", "new_years_eve", "mlk_day", "super_bowl_sunday", "valentines_day",
      "presidents_day", "pulaski_day", "st_patricks_parade", "st_patricks_day", "lent_friday",
      "easter", "orthodox_easter", "passover", "ramadan", "eid_al_fitr", "mothers_day",
      "memorial_day", "fathers_day", "juneteenth", "fourth_of_july", "lollapalooza",
      "bud_billiken_parade", "air_and_water_show", "labor_day", "mexican_independence_parade",
      "rosh_hashanah", "yom_kippur", "chicago_marathon", "halloween", "diwali", "election_day",
      "veterans_day", "thanksgiving", "hanukkah", "christmas_eve", "christmas", "first_day_of_spring",
      "first_day_of_summer", "first_day_of_fall", "first_day_of_winter", "back_to_school");

  /** Keys an event or arc {@code effects} block may use; plus {@code "<who> -> <whom>"} delta maps. */
  public static final Set<String> EFFECT_KEYS = Set.of(
      "mood", "energy", "stress", "partner_satisfaction", "tension", "add_constraints",
      "remove_constraints", "add_relationship_tags", "remove_relationship_tags", "start_arc",
      "relationship_status", "relationship_kind", "end_relationship", "entity_status",
      "creates_entity", "trait_nudges", "knowledge_spread");

  /** Keys role and start {@code requires} blocks may use in arc defs (A.1, A.2, A.9). */
  public static final Set<String> ROLE_REQUIREMENTS = Set.of(
      "relationship_status", "attracted_to", "mutual", "quirk", "not", "has_entity", "single",
      "employed", "unemployed", "has_partner", "trait_min", "trait_max", "circle", "relationship_with");

  public static final Set<String> START_REQUIREMENTS = Set.of(
      "chemistry_min", "faithfulness_a_max", "affinity_min", "affinity_max", "familiarity_min",
      "trait_min", "trait_max", "weather", "season");
}
