package app.radiance.news;

import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Ulid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Fixture headlines for offline runs (SPEC §5.12, M4): each world-day draws a seeded subset of
 * {@code news/fixture-headlines.yaml}, published through the day and already classified, so the news
 * path runs end to end without feeds or a model.
 */
public final class FixtureHeadlines {

  record Template(String title, String region, List<String> topics, double controversy, double salience,
      String sensitivity, Map<String, Double> axis) {}

  private static final List<Template> TEMPLATES = load();

  private final long seed;
  private final ZoneId zone;
  private final int perDay;

  public FixtureHeadlines(long seed, ZoneId zone, int perDay) {
    this.seed = seed;
    this.zone = zone;
    this.perDay = perDay;
  }

  /** Headlines published on {@code date}; the same seed and date always give the same list. */
  public List<Headline> on(LocalDate date) {
    var rng = RandomSource.seeded(seed).derive("headlines:" + date);
    var pool = new ArrayList<>(TEMPLATES);
    var out = new ArrayList<Headline>();
    long start = date.atStartOfDay(zone).toInstant().toEpochMilli();
    int n = Math.min(perDay, pool.size());
    for (int i = 0; i < n; i++) {
      var t = pool.remove(rng.nextInt(pool.size()));
      long published = start + rng.nextInt(24 * 60) * 60_000L;
      double salience = clamp(t.salience() + rng.nextGaussian(0, 0.08), 0.05, 1);
      String id = Ulid.next(published, rng.derive("id:" + i));
      out.add(new Headline(id, "fixture://" + date + "/" + i, "Fixture Wire", t.title(), null, published, published,
          t.region(), t.topics(), t.controversy(), salience, t.sensitivity() == null ? "NORMAL" : t.sensitivity(),
          t.axis(), published));
    }
    out.sort(java.util.Comparator.comparingLong(Headline::publishedAt).thenComparing(Headline::id));
    return out;
  }

  private static List<Template> load() {
    var mapper = YAMLMapper.builder().disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
    try (var in = FixtureHeadlines.class.getResourceAsStream("/news/fixture-headlines.yaml")) {
      if (in == null) throw new IllegalStateException("news/fixture-headlines.yaml is missing");
      return List.copyOf(mapper.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
          new TypeReference<List<Template>>() {}));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
