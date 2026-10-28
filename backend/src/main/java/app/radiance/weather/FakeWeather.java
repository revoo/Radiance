package app.radiance.weather;

import app.radiance.kernel.RandomSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeded weather from Chicago's monthly normals (SPEC §5.12), for {@code offline}, tests and the harness.
 * Any hour is a pure function of the seed and its date, so a forecast and its "actual" agree unless a
 * test says otherwise. Daily highs carry a slow anomaly (a few long waves with seeded phases) so warm
 * and cold spells last days, as heat waves and first warm days need.
 */
public final class FakeWeather implements WeatherSource {

  /** Normal highs and lows by month, °F (NOAA 1991–2020, O'Hare, rounded). */
  static final double[] HIGH = {32, 36, 47, 59, 70, 80, 84, 82, 75, 63, 49, 36};
  static final double[] LOW = {18, 21, 30, 40, 50, 60, 66, 65, 57, 45, 34, 23};
  /** Chance a day has precipitation, by month. */
  static final double[] WET = {0.33, 0.30, 0.36, 0.40, 0.40, 0.36, 0.33, 0.30, 0.30, 0.33, 0.33, 0.33};

  private final long seed;
  private final ZoneId zone;
  private final double phase1;
  private final double phase2;
  private final double phase3;

  public FakeWeather(long seed, ZoneId zone) {
    this.seed = seed;
    this.zone = zone;
    var rng = RandomSource.seeded(seed).derive("weather:phases");
    phase1 = rng.nextDouble(0, 2 * Math.PI);
    phase2 = rng.nextDouble(0, 2 * Math.PI);
    phase3 = rng.nextDouble(0, 2 * Math.PI);
  }

  @Override
  public List<WeatherHour> hours(long fromHour, long toHour) {
    var out = new ArrayList<WeatherHour>();
    for (long h = fromHour; h < toHour; h += WeatherHour.HOUR) out.add(hour(h));
    return out;
  }

  WeatherHour hour(long hourMs) {
    var local = Instant.ofEpochMilli(hourMs).atZone(zone);
    var date = local.toLocalDate();
    var day = day(date);
    int h = local.getHour();
    // Coolest near 6 am, warmest near 3 pm.
    double t = h >= 6 && h <= 15
        ? day.low + (day.high - day.low) * (1 - Math.cos(Math.PI * (h - 6) / 9.0)) / 2
        : day.low + (day.high - day.low) * (1 + Math.cos(Math.PI * ((h + 24 - 15) % 24) / 15.0)) / 2;
    var rng = RandomSource.seeded(seed).derive("weather:hour:" + date + ":" + h);
    double wind = Math.max(0, day.wind + rng.nextGaussian(0, 3));
    boolean wet = day.wet && h >= day.wetStart && h < day.wetStart + day.wetHours;
    double precip = wet ? day.precipPerHour : 0;
    boolean snow = wet && t <= 33;
    double snowIn = snow ? precip * 10 : 0;
    int wmo;
    if (wet && day.storm && !snow) wmo = 95;
    else if (snow) wmo = snowIn >= 0.3 ? 75 : 71;
    else if (wet) wmo = precip >= 0.15 ? 65 : 61;
    else wmo = day.sky;
    return new WeatherHour(hourMs, round(t), round(feelsLike(t, wind)), round(precip), round(snowIn), round(wind),
        wmo, WeatherHour.Kind.FAKE, hourMs);
  }

  record Day(double high, double low, double wind, int sky, boolean wet, int wetStart, int wetHours,
      double precipPerHour, boolean storm) {}

  Day day(LocalDate date) {
    int m = date.getMonthValue() - 1;
    // Interpolate the normals toward the neighboring month so the season moves smoothly.
    double frac = (date.getDayOfMonth() - 15) / 30.0;
    int other = frac >= 0 ? (m + 1) % 12 : (m + 11) % 12;
    double w = Math.abs(frac);
    double high = HIGH[m] * (1 - w) + HIGH[other] * w;
    double low = LOW[m] * (1 - w) + LOW[other] * w;
    long d = date.toEpochDay();
    var rng = RandomSource.seeded(seed).derive("weather:day:" + date);
    double anomaly = 7 * Math.sin(2 * Math.PI * d / 9.0 + phase1) + 4 * Math.sin(2 * Math.PI * d / 23.0 + phase2)
        + 3 * Math.sin(2 * Math.PI * d / 4.0 + phase3) + rng.nextGaussian(0, m <= 3 || m >= 10 ? 5 : 3);
    // Late winter and spring bring the odd warm surge: a 65-degree day after a week in the 40s.
    double surge = rng.chance(m >= 1 && m <= 4 ? 0.08 : m == 9 || m == 10 ? 0.04 : 0.01) ? rng.nextDouble(12, 26) : 0;
    high += anomaly + surge;
    low += anomaly * 0.8 + rng.nextGaussian(0, 2);
    boolean wet = rng.chance(WET[m]);
    int wetStart = rng.nextInt(24);
    int wetHours = 2 + rng.nextInt(9);
    double perHour = rng.chance(0.15) ? rng.nextDouble(0.08, 0.25) : rng.nextDouble(0.01, 0.07);
    boolean storm = wet && high >= 72 && rng.chance(0.35);
    int sky = wet ? 3 : rng.nextInt(4);
    double wind = Math.max(2, rng.nextGaussian(m <= 2 || m >= 10 ? 13 : 9, 4));
    return new Day(high, Math.min(low, high - 6), wind, sky, wet, wetStart, wetHours, perHour, storm);
  }

  /** NWS wind chill at or below 50°F with wind; a rough heat index above 80°F. */
  static double feelsLike(double t, double wind) {
    if (t <= 50 && wind > 3) {
      double v = Math.pow(wind, 0.16);
      return 35.74 + 0.6215 * t - 35.75 * v + 0.4275 * t * v;
    }
    return t > 80 ? t + (t - 80) * 0.3 : t;
  }

  private static double round(double v) {
    return Math.round(v * 100) / 100.0;
  }
}
