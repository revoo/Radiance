package app.radiance.sim;

import app.radiance.content.ContentLibrary;
import app.radiance.content.Durations;
import app.radiance.kernel.WallClock;
import app.radiance.persistence.Sql;
import app.radiance.persistence.WriterLane;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The engine in the app (SPEC §5.1): one {@link WorldEngine} holding the committed world in memory, the
 * {@link ScriptWriter}, and the {@link Knobs} read from {@code radiance.*} (SPEC §9), each falling back
 * to the spec's default.
 */
@Configuration(proxyBeanMethods = false)
class SimConfig {

  @Bean
  Knobs knobs(Environment env) {
    return knobs(env, Knobs.defaults());
  }

  @Bean
  WorldEngine worldEngine(Sql sql, WriterLane lane, ContentLibrary lib, Knobs knobs) {
    return new WorldEngine(sql, lane, lib, knobs);
  }

  @Bean
  ScriptWriter scriptWriter(Sql sql, WriterLane lane, WorldEngine engine, WallClock clock) {
    return new ScriptWriter(sql, lane, engine, ContinuityAuditor.Mode.RECORD, clock);
  }

  static Knobs knobs(Environment env, Knobs d) {
    var b = Binder.get(env);
    return new Knobs(
        hours(env, "radiance.drift.mood-half-life", d.moodHalfLifeHours()),
        days(env, "radiance.social.affinity-half-life", d.affinityHalfLifeDays()),
        d.tensionHalfLifeDays(),
        env.getProperty("radiance.drift.max-trait-drift", Double.class, d.maxTraitDrift()),
        env.getProperty("radiance.storyteller.profile", d.profile()),
        env.getProperty("radiance.storyteller.drama-intensity", Double.class, d.dramaIntensity()),
        env.getProperty("radiance.storyteller.max-major-events-per-actor-per-14d", Integer.class, d.maxMajorEventsPer14d()),
        env.getProperty("radiance.storyteller.max-drama-per-day", Integer.class, d.maxDramaPerDay()),
        map(b, "radiance.storyteller.event-mix", d.eventMix()),
        env.getProperty("radiance.social.user-salience", Double.class, d.userSalience()),
        env.getProperty("radiance.social.max-user-share-of-attention", Double.class, d.maxUserShareOfAttention()),
        map(b, "radiance.social.content-kind-weights", d.contentKindWeights()),
        env.getProperty("radiance.social.impression-sensitivity", Double.class, d.impressionSensitivity()),
        env.getProperty("radiance.social.crush-propensity", Double.class, d.crushPropensity()),
        env.getProperty("radiance.social.dm-initiation-rate", Double.class, d.dmInitiationRate()),
        env.getProperty("radiance.content.edge", Double.class, d.edge()),
        env.getProperty("radiance.romance.temptation", Double.class, d.temptation()),
        env.getProperty("radiance.romance.affairs", Boolean.class, d.affairs()),
        env.getProperty("radiance.news.headlines-per-sim-day", Integer.class, d.headlinesPerDay()),
        map(b, "radiance.news.region-mix", d.regionMix()),
        days(env, "radiance.batch.idle-reduced-after", d.idleReducedAfterDays()),
        days(env, "radiance.batch.idle-summary-after", d.idleSummaryAfterDays()),
        days(env, "radiance.batch.pause-rendering-after", d.pauseRenderingAfterDays()),
        hours(env, "radiance.batch.script-slack", d.scriptSlackHours()),
        env.getProperty("radiance.batch.prime-time-boost", Double.class, d.primeTimeBoost()),
        hours(env, "radiance.backfill.full-window", d.backfillFullHours()),
        days(env, "radiance.backfill.reduced-window", d.backfillReducedDays()),
        days(env, "radiance.backfill.max-gap", d.maxGapDays()));
  }

  private static double hours(Environment env, String key, double fallback) {
    String v = env.getProperty(key);
    return v == null ? fallback : Durations.parse(v).toMinutes() / 60.0;
  }

  private static double days(Environment env, String key, double fallback) {
    String v = env.getProperty(key);
    return v == null ? fallback : Durations.parse(v).toHours() / 24.0;
  }

  private static Map<String, Double> map(Binder b, String key, Map<String, Double> fallback) {
    return b.bind(key, Bindable.mapOf(String.class, Double.class))
        .map(m -> java.util.Collections.unmodifiableMap(new TreeMap<>(m))).orElse(fallback);
  }
}
