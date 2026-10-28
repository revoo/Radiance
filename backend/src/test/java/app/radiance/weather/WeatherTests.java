package app.radiance.weather;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeatherTests {

  static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
  static final ContentLibrary LIB = ClasspathContent.load(List.<ContentProperties.BlacklistEntry>of());

  static long hourOf(LocalDate d, int h) {
    return d.atStartOfDay(CHICAGO).plusHours(h).toInstant().toEpochMilli();
  }

  @Test
  void fakeWeatherIsSeededAndSeasonal() {
    var a = new FakeWeather(7, CHICAGO);
    var b = new FakeWeather(7, CHICAGO);
    long jan = hourOf(LocalDate.of(2027, 1, 1), 0);
    assertThat(a.hours(jan, jan + 48 * WeatherHour.HOUR)).isEqualTo(b.hours(jan, jan + 48 * WeatherHour.HOUR));
    double janMean = mean(a, LocalDate.of(2027, 1, 1), 31);
    double julMean = mean(a, LocalDate.of(2027, 7, 1), 31);
    assertThat(janMean).isBetween(10.0, 40.0);
    assertThat(julMean).isBetween(62.0, 85.0);
  }

  @Test
  void aYearOfFakeWeatherShowsEveryNamedCondition() {
    var w = new FakeWeather(7, CHICAGO);
    var book = new WeatherBook(CHICAGO, LIB.weather());
    var start = LocalDate.of(2027, 1, 1);
    book.putAll(w.hours(hourOf(start.minusDays(8), 0), hourOf(start.plusYears(2), 0)));
    var seen = new java.util.TreeSet<String>();
    for (var d = start; d.isBefore(start.plusYears(2)); d = d.plusDays(1)) seen.addAll(book.conditions(d));
    assertThat(seen).containsAll(LIB.weather().keySet());
  }

  @Test
  void conditionsFollowTheLatestData() {
    var day = LocalDate.of(2027, 1, 10);
    var book = new WeatherBook(CHICAGO, LIB.weather());
    var hours = new ArrayList<WeatherHour>();
    for (int h = 0; h < 24; h++) {
      hours.add(new WeatherHour(hourOf(day, h), 25, 15, 0.03, 0.3, 10, 75, WeatherHour.Kind.FORECAST, 0));
    }
    book.putAll(hours);
    assertThat(book.holds("snow_day", day)).isTrue();
    assertThat(book.at(hourOf(day, 8) + 1000).orElseThrow().describe()).isEqualTo("25°F, feels like 15, heavy snow");
    // the snow never came
    book.putAll(hours.stream().map(h -> new WeatherHour(h.hour(), 30, 25, 0, 0, 5, 3, WeatherHour.Kind.PAST, 1))
        .toList());
    assertThat(book.holds("snow_day", day)).isFalse();
    assertThat(book.conditions(day.plusDays(5))).isEmpty();
  }

  private static double mean(FakeWeather w, LocalDate from, int days) {
    var hs = w.hours(hourOf(from, 0), hourOf(from.plusDays(days), 0));
    return hs.stream().mapToDouble(WeatherHour::tempF).average().orElseThrow();
  }
}
