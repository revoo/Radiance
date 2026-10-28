package app.radiance.weather;

import app.radiance.content.Rules.WeatherCondition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The hours the engine knows, and the named conditions they add up to per world-day (SPEC §5.12, A.7).
 * Conditions are computed from whatever data is loaded, so a book filled from the latest fetch is what
 * airing re-checks a weather-gated item against.
 */
public final class WeatherBook {

  private final ZoneId zone;
  private final Map<String, WeatherCondition> rules;
  private final TreeMap<Long, WeatherHour> hours = new TreeMap<>();

  public WeatherBook(ZoneId zone, Map<String, WeatherCondition> rules) {
    this.zone = zone;
    this.rules = rules;
  }

  public WeatherBook putAll(Collection<WeatherHour> rows) {
    rows.forEach(h -> hours.put(h.hour(), h));
    return this;
  }

  public Optional<WeatherHour> at(long worldTime) {
    var e = hours.floorEntry(worldTime);
    return e == null || worldTime - e.getKey() >= WeatherHour.HOUR ? Optional.empty() : Optional.of(e.getValue());
  }

  public List<WeatherHour> day(LocalDate date) {
    long from = date.atStartOfDay(zone).toInstant().toEpochMilli();
    long to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    return new ArrayList<>(hours.subMap(from, true, to, false).values());
  }

  public LocalDate dateOf(long worldTime) {
    return Instant.ofEpochMilli(worldTime).atZone(zone).toLocalDate();
  }

  /** The named conditions that hold on {@code date}; empty if the day has no data. */
  public TreeSet<String> conditions(LocalDate date) {
    var out = new TreeSet<String>();
    var hs = day(date);
    if (hs.isEmpty()) return out;
    rules.forEach((name, c) -> {
      if (holds(c, date, hs)) out.add(name);
    });
    return out;
  }

  public boolean holds(String condition, LocalDate date) {
    var c = rules.get(condition);
    if (c == null) throw new IllegalArgumentException("unknown weather condition '" + condition + "'");
    var hs = day(date);
    return !hs.isEmpty() && holds(c, date, hs);
  }

  private boolean holds(WeatherCondition c, LocalDate date, List<WeatherHour> hs) {
    double snow = hs.stream().mapToDouble(WeatherHour::snowIn).sum();
    double precip = hs.stream().mapToDouble(WeatherHour::precipIn).sum();
    double high = high(hs);
    double feelsLow = hs.stream().mapToDouble(WeatherHour::feelsLikeF).min().orElse(0);
    int wmoMax = hs.stream().mapToInt(WeatherHour::wmo).max().orElse(0);
    if (c.snowfallIn() != null && !c.snowfallIn().contains(snow)) return false;
    if (c.precipIn() != null && !c.precipIn().contains(precip)) return false;
    if (c.highF() != null && !c.highF().contains(high)) return false;
    if (c.feelsLikeLowF() != null && !c.feelsLikeLowF().contains(feelsLow)) return false;
    if (c.wmoMax() != null && wmoMax > c.wmoMax()) return false;
    if (!c.wmoAny().isEmpty() && hs.stream().noneMatch(h -> c.wmoAny().contains(h.wmo()))) return false;
    if (c.priorWeekHighF() != null) {
      double prior = Double.NEGATIVE_INFINITY;
      for (int i = 1; i <= 7; i++) {
        var p = day(date.minusDays(i));
        if (p.isEmpty()) return false;
        prior = Math.max(prior, high(p));
      }
      if (!c.priorWeekHighF().contains(prior)) return false;
    }
    if (c.streakDays() != null && c.highF() != null) {
      for (int i = 1; i < c.streakDays(); i++) {
        var p = day(date.minusDays(i));
        if (p.isEmpty() || !c.highF().contains(high(p))) return false;
      }
    }
    return true;
  }

  private static double high(List<WeatherHour> hs) {
    return hs.stream().mapToDouble(WeatherHour::tempF).max().orElse(0);
  }
}
