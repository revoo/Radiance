package app.radiance.content;

import app.radiance.content.Vocab.BeatKind;
import app.radiance.content.Vocab.Category;
import app.radiance.content.Vocab.EntityKind;
import app.radiance.content.Vocab.PostOutput;
import app.radiance.content.Vocab.Stance;
import app.radiance.kernel.Visibility;
import java.util.List;
import java.util.Map;

/**
 * The def schemas (SPEC §5.2–§5.5, Appendix A), bound from YAML with snake_case keys. Unknown keys fail
 * loading. Lists and maps default to empty, so the engine never sees null collections.
 */
public final class Defs {

  private Defs() {}

  static <T> List<T> list(List<T> l) {
    return l == null ? List.of() : List.copyOf(l);
  }

  static <K, V> Map<K, V> map(Map<K, V> m) {
    return m == null ? Map.of() : Map.copyOf(m);
  }

  // ---------------------------------------------------------------- quirks (SPEC §5.2, A.3)

  /** @param params names of parameters, e.g. {@code [team]} for {@code sports_diehard:<team>} */
  public record QuirkDef(
      String id, String description, List<String> params, Mechanics mechanics, String voiceHint) {
    public QuirkDef {
      params = list(params);
      mechanics = mechanics == null ? new Mechanics(null, null, null, null, null, null) : mechanics;
    }
  }

  /**
   * @param audienceBias stance biases for people responding to this resident, keyed by situation
   *     ({@code on_car_posts})
   * @param postingRate multiplier on the resident's posting rate (lurker 0.1, oversharer 3)
   */
  public record Mechanics(
      Map<String, Double> arcMultipliers,
      Map<String, Double> eventMultipliers,
      Map<Stance, Double> stanceBias,
      Map<String, Map<Stance, Double>> audienceBias,
      Map<String, Double> traitNudges,
      Double postingRate) {
    public Mechanics {
      arcMultipliers = map(arcMultipliers);
      eventMultipliers = map(eventMultipliers);
      stanceBias = map(stanceBias);
      audienceBias = map(audienceBias);
      traitNudges = map(traitNudges);
    }
  }

  // ---------------------------------------------------------------- constraints (SPEC §5.5, A.4)

  public record ConstraintDef(
      String id, List<String> params, Planner planner, String prose, boolean reasonPublic,
      Linter linter) {
    public ConstraintDef {
      params = list(params);
      planner = planner == null ? new Planner(null, null) : planner;
    }
  }

  /** {@code excludes_event_tags}: events tagged so can't happen; {@code blocks}: event categories. */
  public record Planner(List<String> excludesEventTags, List<Category> blocksCategories) {
    public Planner {
      excludesEventTags = list(excludesEventTags);
      blocksCategories = list(blocksCategories);
    }
  }

  public record Linter(List<String> lexicon, String judge) {
    public Linter {
      lexicon = list(lexicon);
    }
  }

  // ---------------------------------------------------------------- events (SPEC §5.4, A.5)

  public record EventDef(
      String id,
      Category category,
      String description,
      When when,
      Trigger trigger,
      double baseRate,
      Map<String, Double> modifiers,
      Conditions requires,
      Conditions excludes,
      Map<String, String> participants,
      Double cooldownDays,
      int importance,
      List<String> tags,
      Output output,
      Map<String, Object> effects) {
    public EventDef {
      modifiers = map(modifiers);
      requires = requires == null ? Conditions.NONE : requires;
      excludes = excludes == null ? Conditions.NONE : excludes;
      participants = map(participants);
      tags = list(tags);
      effects = effects == null ? Map.of() : effects;
    }
  }

  /** Calendar dates (any of), a named weather condition, or a season gate the event. */
  public record When(List<String> calendar, String weather, List<String> seasons) {
    public When {
      calendar = list(calendar);
      seasons = list(seasons);
    }
  }

  public record Trigger(HeadlineTrigger headline) {}

  public record HeadlineTrigger(double minInterest) {}

  /**
   * @param tags constraint tags, optionally with a parameter ({@code injured:knee})
   * @param arcs arc ids or {@code arc:phase}
   * @param entities entity kinds the actor owns ({@code PET}, {@code CAR})
   * @param traditions faith traditions, lowercase ({@code hindu}, {@code catholic}); any of them
   */
  public record Conditions(
      List<String> tags, List<String> arcs, List<String> quirks, List<Vocab.StatusKind> relationshipStatus,
      List<EntityKind> entities, List<String> traditions) {
    static final Conditions NONE = new Conditions(null, null, null, null, null, null);

    public Conditions {
      tags = list(tags);
      arcs = list(arcs);
      quirks = list(quirks);
      relationshipStatus = list(relationshipStatus);
      entities = list(entities);
      traditions = list(traditions);
    }
  }

  /**
   * @param stanceFrom how stance is chosen for takes: {@code politics_axes} for news (SPEC §5.4)
   */
  public record Output(PostOutput post, String brief, String media, String stanceFrom) {}

  // ---------------------------------------------------------------- arcs (SPEC §5.5, A.1, A.2, A.9)

  public record ArcDef(
      String id,
      Category category,
      String description,
      Map<String, Role> roles,
      Start start,
      List<String> exclusiveWith,
      int maxSilenceDays,
      Recurrence recurrence,
      String abandon,
      boolean sensitive,
      List<Phase> phases) {
    public ArcDef {
      roles = map(roles);
      exclusiveWith = list(exclusiveWith);
      phases = list(phases);
    }
  }

  public record Role(Map<String, Object> requires) {
    public Role {
      requires = requires == null ? Map.of() : requires;
    }
  }

  public record Start(double baseRatePerYear, Map<String, Object> requires, String scaleBy) {
    public Start {
      requires = requires == null ? Map.of() : requires;
    }
  }

  public record Recurrence(List<Integer> cooldownDays) {
    public Recurrence {
      cooldownDays = list(cooldownDays);
    }
  }

  /**
   * @param durationDays {@code [min, max]}; absent on terminal phases
   * @param knowers role names, or {@code closest_friend_of:<role>}; absent means the phase's
   *     visibility decides (PUBLIC: everyone)
   * @param constraints constraint tags; {@code {role}} placeholders name the arc's actors
   */
  public record Phase(
      String id,
      List<Integer> durationDays,
      Visibility visibility,
      List<String> knowers,
      List<Beat> beats,
      EntryEvent entryEvent,
      List<String> constraints,
      List<Transition> transitions,
      String next,
      boolean terminal) {
    public Phase {
      durationDays = list(durationDays);
      knowers = list(knowers);
      beats = list(beats);
      constraints = list(constraints);
      transitions = list(transitions);
    }
  }

  public record Beat(BeatKind kind, String brief, Integer maxPerWeek) {}

  public record EntryEvent(
      String type, int importance, PostOutput post, String media, Map<String, Object> effects,
      CreatesEntity createsEntity) {
    public EntryEvent {
      effects = effects == null ? Map.of() : effects;
    }
  }

  public record CreatesEntity(EntityKind kind, String fromTable) {}

  public record Transition(String to, double p) {}
}
