package app.radiance.weather;

import static app.radiance.persistence.Table.col;

import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.Collection;
import java.util.List;

public final class WeatherRepository {

  static final Table<WeatherHour> TABLE =
      new Table<>(
          "weather_hour",
          List.of("hour"),
          r -> new WeatherHour(r.lng("hour"), r.dbl("temp_f"), r.dbl("feels_like_f"), r.dbl("precip_in"),
              r.dbl("snow_in"), r.dbl("wind_mph"), (int) r.lng("wmo_code"), r.enm("kind", WeatherHour.Kind.class),
              r.lng("fetched_at")),
          col("hour", WeatherHour::hour),
          col("temp_f", WeatherHour::tempF),
          col("feels_like_f", WeatherHour::feelsLikeF),
          col("precip_in", WeatherHour::precipIn),
          col("snow_in", WeatherHour::snowIn),
          col("wind_mph", WeatherHour::windMph),
          col("wmo_code", WeatherHour::wmo),
          col("kind", WeatherHour::kind),
          col("fetched_at", WeatherHour::fetchedAt));

  private final Sql sql;

  public WeatherRepository(Sql sql) {
    this.sql = sql;
  }

  public List<WeatherHour> between(long fromHour, long toHour) {
    return TABLE.where(sql, "hour >= ? AND hour < ? ORDER BY hour", fromHour, toHour);
  }

  /** Newer data replaces older forecasts (SPEC §5.12). */
  public UnitOfWork save(UnitOfWork uow, Collection<WeatherHour> rows) {
    return TABLE.upsert(uow, rows);
  }

  /** Like {@link #save}, but a fake hour (a fallback after a failed fetch) never replaces real data. */
  public UnitOfWork saveKeepingReal(UnitOfWork uow, Collection<WeatherHour> rows) {
    if (rows.isEmpty()) return uow;
    long from = rows.stream().mapToLong(WeatherHour::hour).min().orElseThrow();
    long to = rows.stream().mapToLong(WeatherHour::hour).max().orElseThrow() + WeatherHour.HOUR;
    var real = between(from, to).stream().filter(h -> h.kind() != WeatherHour.Kind.FAKE).map(WeatherHour::hour)
        .collect(java.util.stream.Collectors.toSet());
    return save(uow, rows.stream().filter(h -> h.kind() != WeatherHour.Kind.FAKE || !real.contains(h.hour())).toList());
  }
}
