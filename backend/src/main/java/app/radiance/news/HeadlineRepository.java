package app.radiance.news;

import static app.radiance.persistence.Table.col;

import app.radiance.kernel.Json;
import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;

public final class HeadlineRepository {

  private static final TypeReference<List<String>> TOPICS = new TypeReference<>() {};
  private static final TypeReference<Map<String, Double>> AXES = new TypeReference<>() {};

  static final Table<Headline> TABLE =
      new Table<>(
          "headline",
          List.of("id"),
          r -> new Headline(r.str("id"), r.str("url"), r.str("source"), r.str("title"), r.str("snippet"),
              r.lng("published_at"), r.lng("fetched_at"), r.str("region"),
              r.isNull("topics_json") ? List.of() : Json.MAPPER.readValue(r.str("topics_json"), TOPICS),
              r.isNull("controversy") ? 0 : r.dbl("controversy"), r.isNull("salience") ? 0 : r.dbl("salience"),
              r.str("sensitivity"),
              r.isNull("axis_signal_json") ? Map.of() : Json.MAPPER.readValue(r.str("axis_signal_json"), AXES),
              r.lngOrNull("classified_at")),
          col("id", Headline::id),
          col("url", Headline::url),
          col("source", Headline::source),
          col("title", Headline::title),
          col("snippet", Headline::snippet),
          col("published_at", Headline::publishedAt),
          col("fetched_at", Headline::fetchedAt),
          col("region", Headline::region),
          col("topics_json", h -> Json.write(h.topics())),
          col("controversy", Headline::controversy),
          col("salience", Headline::salience),
          col("sensitivity", Headline::sensitivity),
          col("axis_signal_json", h -> Json.write(h.axisSignal())),
          col("classified_at", Headline::classifiedAt));

  private final Sql sql;

  public HeadlineRepository(Sql sql) {
    this.sql = sql;
  }

  /** Classified headlines published in {@code [from, to)} (uses {@code ix_headline_pub}). */
  public List<Headline> publishedBetween(long from, long to) {
    return TABLE.where(sql, "published_at >= ? AND published_at < ? AND classified_at IS NOT NULL"
        + " ORDER BY published_at, id", from, to);
  }

  /** Dedupes by id; the URL is unique too. */
  public UnitOfWork save(UnitOfWork uow, Collection<Headline> rows) {
    return TABLE.insert(uow, rows);
  }
}
