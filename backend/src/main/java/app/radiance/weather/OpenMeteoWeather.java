package app.radiance.weather;

import app.radiance.kernel.Json;
import app.radiance.kernel.WallClock;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Real hourly weather from Open-Meteo's forecast API (SPEC §5.12, Appendix C), which needs no key. Any hour it
 * can't cover (more than 92 days back, past its 16-day forecast, or a failed call) comes from the fallback.
 */
public final class OpenMeteoWeather implements WeatherSource {

  private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeather.class);
  public static final URI ENDPOINT = URI.create("https://api.open-meteo.com/v1/forecast");
  private static final long DAY = 24 * WeatherHour.HOUR;
  private static final int MAX_PAST_DAYS = 92;
  private static final int MAX_FORECAST_DAYS = 16;

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final URI endpoint;
  private final double latitude;
  private final double longitude;
  private final ZoneId zone;
  private final WallClock clock;
  private final WeatherSource fallback;

  public OpenMeteoWeather(URI endpoint, double latitude, double longitude, ZoneId zone, WallClock clock, WeatherSource fallback) {
    this.endpoint = endpoint;
    this.latitude = latitude;
    this.longitude = longitude;
    this.zone = zone;
    this.clock = clock;
    this.fallback = fallback;
  }

  @Override
  public List<WeatherHour> hours(long fromHour, long toHour) {
    long now = clock.nowMs();
    Map<Long, WeatherHour> real = Map.of();
    try {
      real = parse(get(uri(fromHour, toHour, now)), now);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("Open-Meteo call failed; using the fallback weather: {}", e.toString());
    }
    var out = new ArrayList<WeatherHour>();
    var fill = fallback.hours(fromHour, toHour).iterator();
    for (long h = fromHour; h < toHour; h += WeatherHour.HOUR) {
      var other = fill.next();
      var hour = real.get(h);
      out.add(hour != null ? hour : other);
    }
    return out;
  }

  URI uri(long fromHour, long toHour, long now) {
    long pastDays = Math.clamp(ceilDays(now - fromHour), 0, MAX_PAST_DAYS);
    long forecastDays = Math.clamp(ceilDays(toHour - now) + 1, 2, MAX_FORECAST_DAYS);
    return URI.create(endpoint + "?latitude=" + latitude + "&longitude=" + longitude
        + "&hourly=temperature_2m,apparent_temperature,precipitation,snowfall,weather_code,wind_speed_10m"
        + "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch&timeformat=unixtime"
        + "&timezone=" + URLEncoder.encode(zone.getId(), StandardCharsets.UTF_8)
        + "&past_days=" + pastDays + "&forecast_days=" + forecastDays);
  }

  private static long ceilDays(long ms) {
    return ms <= 0 ? 0 : (ms + DAY - 1) / DAY;
  }

  private String get(URI uri) throws Exception {
    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
    return response.body();
  }

  /** Hours with any missing reading are left out, for the fallback to fill. */
  static Map<Long, WeatherHour> parse(String body, long now) {
    var hourly = Json.tree(body).path("hourly");
    var time = hourly.path("time");
    var out = new HashMap<Long, WeatherHour>();
    for (int i = 0; i < time.size(); i++) {
      long hour = time.get(i).asLong() * 1000;
      var temp = hourly.path("temperature_2m").path(i);
      var feels = hourly.path("apparent_temperature").path(i);
      var precip = hourly.path("precipitation").path(i);
      var snow = hourly.path("snowfall").path(i);
      var wind = hourly.path("wind_speed_10m").path(i);
      var code = hourly.path("weather_code").path(i);
      if (!(number(temp) && number(feels) && number(precip) && number(snow) && number(wind) && number(code))) continue;
      out.put(hour, new WeatherHour(hour, temp.asDouble(), feels.asDouble(), precip.asDouble(), snow.asDouble(),
          wind.asDouble(), code.asInt(), hour < now ? WeatherHour.Kind.PAST : WeatherHour.Kind.FORECAST, now));
    }
    return out;
  }

  private static boolean number(JsonNode n) {
    return n.isNumber();
  }
}
