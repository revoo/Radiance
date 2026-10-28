package app.radiance.sim;

import java.util.Map;

/**
 * The simulation's knobs (SPEC §9: {@code batch}, {@code backfill}, {@code storyteller}, {@code social},
 * {@code romance}, {@code drift}, {@code news}, {@code content.edge}), with the spec's defaults. The pure
 * core takes them as a value; the live-tunable ones are overridden from {@code world.settings_json}.
 */
public record Knobs(
    // drift and decay
    double moodHalfLifeHours, double affinityHalfLifeDays, double tensionHalfLifeDays, double maxTraitDrift,
    // storyteller
    String profile, double dramaIntensity, int maxMajorEventsPer14d, int maxDramaPerDay,
    Map<String, Double> eventMix,
    // social
    double userSalience, double maxUserShareOfAttention, Map<String, Double> contentKindWeights,
    double impressionSensitivity, double crushPropensity, double dmInitiationRate, double edge,
    // romance
    double temptation, boolean affairs,
    // news
    int headlinesPerDay, Map<String, Double> regionMix,
    // batch, fidelity and backfill (days and hours)
    double idleReducedAfterDays, double idleSummaryAfterDays, double pauseRenderingAfterDays,
    double scriptSlackHours, double primeTimeBoost, double backfillFullHours, double backfillReducedDays,
    double maxGapDays) {

  public static Knobs defaults() {
    return new Knobs(36, 21, 2, 10,
        "STEADY", 1.0, 2, 2,
        Map.of("DAILY", 0.25, "NEWS", 0.25, "LIFE", 0.15, "HOBBY", 0.12, "FAMILY", 0.12, "SOCIAL", 0.08,
            "DRAMA", 0.03),
        2.0, 0.5, Map.of("PHOTO", 1.3, "TEXT", 1.0, "LINK", 0.7, "COMMENT", 0.45, "REPLY", 0.3),
        1.0, 1.0, 1.0, 1.0,
        1.0, true,
        25, Map.of("NATIONAL", 0.60, "WORLD", 0.30, "LOCAL", 0.10),
        7, 21, 60, 6, 1.5, 48, 7, 30);
  }

  public Knobs withProfile(String p) {
    return new Knobs(moodHalfLifeHours, affinityHalfLifeDays, tensionHalfLifeDays, maxTraitDrift, p, dramaIntensity,
        maxMajorEventsPer14d, maxDramaPerDay, eventMix, userSalience, maxUserShareOfAttention, contentKindWeights,
        impressionSensitivity, crushPropensity, dmInitiationRate, edge, temptation, affairs, headlinesPerDay,
        regionMix, idleReducedAfterDays, idleSummaryAfterDays, pauseRenderingAfterDays, scriptSlackHours,
        primeTimeBoost, backfillFullHours, backfillReducedDays, maxGapDays);
  }
}
