package app.radiance.weather;

/**
 * One hour of weather (SPEC §5.12), in °F, mph and inches.
 *
 * @param hour epoch ms at the top of the hour, world time
 * @param wmo the WMO weather code (Appendix C): 0 clear … 3 overcast, 61–65 rain, 71–75 snow, 95+ storms
 */
public record WeatherHour(
    long hour, double tempF, double feelsLikeF, double precipIn, double snowIn, double windMph, int wmo,
    Kind kind, long fetchedAt) {

  public enum Kind {
    FORECAST,
    PAST,
    FAKE
  }

  public static final long HOUR = 3_600_000L;

  /** Plain words for a packet's Today block: "34°F, feels like 27, light snow". */
  public String describe() {
    var sb = new StringBuilder().append(Math.round(tempF)).append("°F");
    if (Math.abs(feelsLikeF - tempF) >= 4) sb.append(", feels like ").append(Math.round(feelsLikeF));
    sb.append(", ").append(sky());
    return sb.toString();
  }

  String sky() {
    if (wmo >= 95) return "thunderstorms";
    if (wmo >= 71 && wmo <= 77) return snowIn >= 0.3 ? "heavy snow" : "light snow";
    if (wmo >= 51 && wmo <= 67) return precipIn >= 0.15 ? "heavy rain" : "light rain";
    return switch (wmo) {
      case 0 -> "clear";
      case 1 -> "mostly clear";
      case 2 -> "partly cloudy";
      default -> "overcast";
    };
  }
}
