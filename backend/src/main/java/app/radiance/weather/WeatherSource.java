package app.radiance.weather;

import java.util.List;

/** Where hourly weather comes from (SPEC §5.12): Open-Meteo (M10) or {@link FakeWeather}. */
public interface WeatherSource {

  /** Every hour in {@code [fromHour, toHour)}, in order; both are tops of hours, world time. */
  List<WeatherHour> hours(long fromHour, long toHour);
}
