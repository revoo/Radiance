package app.radiance.weather;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.persistence.TestDatabase;
import app.radiance.persistence.UnitOfWork;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Open-Meteo over real HTTP to a local server that answers as the real API does (checked 2026-11-02). */
class OpenMeteoWeatherTests {

  static final long HOUR = WeatherHour.HOUR;
  static final long T0 = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli();
  static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

  /** Four hours from T0: the third has a gap in its readings, as hours at the end of a model's range do. */
  static final String BODY = """
      {"utc_offset_seconds":-21600,"timezone":"America/Chicago",
       "hourly_units":{"time":"unixtime","snowfall":"inch"},
       "hourly":{"time":[%d,%d,%d,%d],
        "temperature_2m":[34.5,36.0,null,40.0],"apparent_temperature":[27.1,30.0,null,38.0],
        "precipitation":[0.02,0.0,null,0.0],"snowfall":[0.2,0.0,null,0.0],
        "weather_code":[71,3,null,0],"wind_speed_10m":[12.5,8.0,null,4.0]}}
      """.formatted(T0 / 1000, (T0 + HOUR) / 1000, (T0 + 2 * HOUR) / 1000, (T0 + 3 * HOUR) / 1000);

  @TempDir Path dir;
  TestDatabase db;
  HttpServer server;
  final AtomicReference<String> query = new AtomicReference<>();
  volatile int status = 200;
  volatile String body = BODY;

  @BeforeEach
  void start() throws Exception {
    db = TestDatabase.leased(dir);
    db.clock.set(T0 + HOUR + 60_000);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/forecast", ex -> {
      query.set(ex.getRequestURI().getRawQuery());
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(status, bytes.length);
      ex.getResponseBody().write(bytes);
      ex.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    db.close();
  }

  OpenMeteoWeather weather() {
    var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/forecast");
    return new OpenMeteoWeather(uri, 41.88, -87.63, CHICAGO, db.clock, new FakeWeather(7, CHICAGO));
  }

  @Test
  void readsHoursInOurUnitsAndMarksThePast() {
    var hours = weather().hours(T0, T0 + 2 * HOUR);
    assertThat(hours).hasSize(2);
    var first = hours.getFirst();
    assertThat(first.hour()).isEqualTo(T0);
    assertThat(first.tempF()).isEqualTo(34.5);
    assertThat(first.feelsLikeF()).isEqualTo(27.1);
    assertThat(first.precipIn()).isEqualTo(0.02);
    assertThat(first.snowIn()).isEqualTo(0.2);
    assertThat(first.windMph()).isEqualTo(12.5);
    assertThat(first.wmo()).isEqualTo(71);
    assertThat(first.kind()).isEqualTo(WeatherHour.Kind.PAST);
    assertThat(first.describe()).isEqualTo("35°F, feels like 27, light snow");
    assertThat(weather().hours(T0 + 3 * HOUR, T0 + 4 * HOUR).getFirst().kind()).isEqualTo(WeatherHour.Kind.FORECAST);
  }

  @Test
  void asksForTheRightUnitsZoneAndRange() {
    weather().hours(T0 - 9 * 24 * HOUR, T0 + 3 * 24 * HOUR);
    assertThat(query.get()).contains("latitude=41.88", "longitude=-87.63", "temperature_unit=fahrenheit",
        "wind_speed_unit=mph", "precipitation_unit=inch", "timeformat=unixtime", "timezone=America%2FChicago",
        "past_days=10", "forecast_days=4");
  }

  @Test
  void clampsTheRangeToWhatTheApiAllows() {
    weather().hours(T0 - 200 * 24 * HOUR, T0 - 199 * 24 * HOUR);
    assertThat(query.get()).contains("past_days=92", "forecast_days=2");
    weather().hours(T0 + 39 * 24 * HOUR, T0 + 40 * 24 * HOUR);
    assertThat(query.get()).contains("past_days=0", "forecast_days=16");
  }

  @Test
  void theFallbackFillsMissingReadingsAndHoursOutOfRange() {
    var hours = weather().hours(T0 - HOUR, T0 + 4 * HOUR);
    assertThat(hours).hasSize(5);
    assertThat(hours).extracting(WeatherHour::kind).containsExactly(WeatherHour.Kind.FAKE, WeatherHour.Kind.PAST,
        WeatherHour.Kind.PAST, WeatherHour.Kind.FAKE, WeatherHour.Kind.FORECAST);
    assertThat(hours).extracting(WeatherHour::hour)
        .containsExactly(T0 - HOUR, T0, T0 + HOUR, T0 + 2 * HOUR, T0 + 3 * HOUR);
  }

  @Test
  void aFailedCallFallsBackInsteadOfFailingTheBatch() {
    status = 500;
    assertThat(weather().hours(T0, T0 + 2 * HOUR)).extracting(WeatherHour::kind)
        .containsOnly(WeatherHour.Kind.FAKE);
    status = 200;
    body = "<html>not json";
    assertThat(weather().hours(T0, T0 + 2 * HOUR)).extracting(WeatherHour::kind)
        .containsOnly(WeatherHour.Kind.FAKE);
    server.stop(0);
    assertThat(weather().hours(T0, T0 + HOUR)).extracting(WeatherHour::kind).containsOnly(WeatherHour.Kind.FAKE);
  }

  @Test
  void fakeHoursNeverReplaceRealOnes() {
    var repo = new WeatherRepository(db.sql);
    var real = weather().hours(T0, T0 + 2 * HOUR);
    db.lane.run(() -> db.sql.commit(repo.saveKeepingReal(new UnitOfWork(), real)));
    var fake = new FakeWeather(7, CHICAGO).hours(T0, T0 + 3 * HOUR);
    db.lane.run(() -> db.sql.commit(repo.saveKeepingReal(new UnitOfWork(), fake)));

    var stored = repo.between(T0, T0 + 3 * HOUR);
    assertThat(stored).extracting(WeatherHour::kind).containsExactly(WeatherHour.Kind.PAST, WeatherHour.Kind.PAST,
        WeatherHour.Kind.FAKE);
    assertThat(stored.getFirst().tempF()).isEqualTo(34.5);

    db.clock.set(T0 + 2 * HOUR);
    var newer = List.of(new WeatherHour(T0 + HOUR, 50, 50, 0, 0, 0, 0, WeatherHour.Kind.PAST, T0 + 2 * HOUR));
    db.lane.run(() -> db.sql.commit(repo.saveKeepingReal(new UnitOfWork(), newer)));
    assertThat(repo.between(T0 + HOUR, T0 + 2 * HOUR).getFirst().tempF()).as("newer data replaces older").isEqualTo(50);
  }
}
