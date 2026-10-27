package app.radiance.content;

import app.radiance.content.Vocab.Temperament;
import java.util.List;
import java.util.Map;

/** The rules files in {@code content/rules/} (SPEC §5.2, §5.3, §5.8, §5.12, A.6–A.8). */
public final class Rules {

  private Rules() {}

  // ---------------------------------------------------------------- temperament.yaml (A.8)

  /**
   * @param jitter genesis adds up to ±this fraction to each preset number
   * @param moodEffect in a bad mood, bad interactions land up to this much harder, good ones softer
   * @param taperZone changes shrink linearly over this many points before a bound
   */
  public record TemperamentRules(
      Map<Temperament, Preset> presets, double moodEffect, double taperZone, double jitter) {
    public TemperamentRules {
      presets = Defs.map(presets);
    }
  }

  public record Preset(double baseline, double goodGain, double badGain, double floor, double ceiling) {}

  // ---------------------------------------------------------------- weather.yaml (A.7)

  public record Range(Double min, Double max) {
    public boolean contains(double v) {
      return (min == null || v >= min) && (max == null || v <= max);
    }
  }

  /** A named condition, computed per world-day from hourly weather. Every given test must hold. */
  public record WeatherCondition(
      Range snowfallIn, Range highF, Range priorWeekHighF, Range feelsLikeLowF, Integer streakDays,
      List<Integer> wmoAny, Integer wmoMax, Range precipIn) {
    public WeatherCondition {
      wmoAny = Defs.list(wmoAny);
    }
  }

  // ---------------------------------------------------------------- relationship-deltas.yaml (§5.3)

  public record Deltas(double affinity, double trust, double attraction, double familiarity, double respect) {
    public static final Deltas ZERO = new Deltas(0, 0, 0, 0, 0);
  }

  /** How one aired interaction moves each side, before temperament and mood. */
  public record InteractionDeltas(Deltas target, Deltas actor, Deltas bystanders) {
    public InteractionDeltas {
      target = target == null ? Deltas.ZERO : target;
      actor = actor == null ? Deltas.ZERO : actor;
      bystanders = bystanders == null ? Deltas.ZERO : bystanders;
    }
  }

  /**
   * @param familiarityHalfLifeDays familiarity decays toward 0 without contact
   * @param kindBaselines the affinity a relationship kind relaxes toward (plus temperament baseline)
   */
  public record RelationshipRules(
      Map<String, InteractionDeltas> interactions,
      double familiarityHalfLifeDays,
      Map<String, Double> kindBaselines,
      double friendFamiliarityMin,
      double friendAffinityAboveBaseline) {
    public RelationshipRules {
      interactions = Defs.map(interactions);
      kindBaselines = Defs.map(kindBaselines);
    }
  }

  // ---------------------------------------------------------------- engagement.yaml (§5.8)

  /** The weights of the engagement utility (SPEC §5.8); θ_comment must exceed θ_react. */
  public record EngagementRules(
      double wRel, double wFam, double wTopic, double wCtrv, double wDrama, double wRecip,
      double wMood, double wFat, double wSat, double steepness, double thetaReact,
      double thetaComment, double noiseSd, int sessionsPerDayMin, int sessionsPerDayMax,
      int sessionMinutesMin, int sessionMinutesMax) {}

  // ---------------------------------------------------------------- pacing.yaml (A.6)

  /** Durations are strings like {@code 2s}, {@code 4m}; ranges are {@code [min, max]}. */
  public record PacingRules(
      ResponseCurve responseCurve,
      Map<String, Bucket> buckets,
      Map<String, Double> priorityWeights,
      Map<String, String> hold,
      BreathingRoom breathingRoom,
      Typing typing,
      DmPacing dm,
      Hooks hooks) {
    public PacingRules {
      buckets = Defs.map(buckets);
      priorityWeights = Defs.map(priorityWeights);
      hold = Defs.map(hold);
    }
  }

  public record ResponseCurve(
      Timing reflexReaction, CommentTiming comment, ReplyTiming replyToR, double dramaTailMultiplier) {}

  public record Timing(List<String> first, List<String> spacing) {}

  public record CommentTiming(List<String> first, double gapGrowth, String tail) {}

  public record ReplyTiming(List<String> addresseeFirst, int maxPileOn) {}

  public record Bucket(int perMinute, int burst, Boolean groupReactions) {}

  public record BreathingRoom(int afterReleases, String within, List<String> pause) {}

  public record Typing(boolean showWhenLate, String giveUpAfter) {}

  public record DmPacing(List<String> typingStarts, int msPerChar, String maxTypingDelay, List<String> burstGap) {}

  public record Hooks(String quietGap, String minInterval, List<String> kinds) {
    public Hooks {
      kinds = Defs.list(kinds);
    }
  }
}
