package app.radiance.weather;

import java.time.ZoneId;

/** Makes a world's {@link WeatherSource}. */
@FunctionalInterface
public interface WeatherSources {

  WeatherSource forWorld(long seed, ZoneId zone);
}
