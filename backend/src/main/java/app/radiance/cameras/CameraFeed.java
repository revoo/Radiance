package app.radiance.cameras;

import app.radiance.kernel.Json;
import app.radiance.kernel.WallClock;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hand-picked public cameras around Chicago (ADR 0020). The server does the fetching: the list every few minutes, each
 * image at most once a minute. It only ever connects to the configured host, and viewers ask by our id, never by URL.
 */
public final class CameraFeed {

  /** A camera we show: {@code match} is part of its snapshot's file name, the one stable thing about it. */
  public record Spot(String id, String name, String match) {}

  /** A camera that's up, and when its picture was taken. */
  public record Camera(String id, String name, long takenAt) {}

  public record Image(byte[] jpeg, long takenAt) {}

  private record Listed(String url, long takenAt) {}

  private record Cached(Image image, long fetchedAt) {}

  private static final Logger log = LoggerFactory.getLogger(CameraFeed.class);
  static final long LIST_EVERY = 5 * 60_000L;
  static final long IMAGE_EVERY = 60_000L;
  static final long KEEP_FAILED_FOR = 15 * 60_000L;
  private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
  private static final int MAX_LIST_BYTES = 256 * 1024;

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
  private final boolean enabled;
  private final URI dataset;
  private final String imageHost;
  private final String imageScheme;
  private final List<Spot> spots;
  private final WallClock clock;
  private final ReentrantLock listLock = new ReentrantLock();
  private final Map<String, ReentrantLock> imageLocks = new ConcurrentHashMap<>();
  private final Map<String, Cached> images = new ConcurrentHashMap<>();
  private volatile Map<String, Listed> listed = Map.of();
  private volatile long listedAt = Long.MIN_VALUE / 2;

  /** {@code imageOrigin} is the only place images may come from, such as {@code https://cctv.travelmidwest.com}. */
  public CameraFeed(boolean enabled, URI dataset, String imageOrigin, List<Spot> spots, WallClock clock) {
    this.enabled = enabled;
    this.dataset = dataset;
    var origin = URI.create(imageOrigin);
    this.imageHost = origin.getHost();
    this.imageScheme = origin.getScheme();
    this.spots = List.copyOf(spots);
    this.clock = clock;
  }

  /** The cameras that are up right now, in the order they were picked. */
  public List<Camera> cameras() {
    if (!enabled) return List.of();
    refreshList();
    var now = listed;
    var out = new ArrayList<Camera>();
    for (var spot : spots) {
      var row = now.get(spot.id());
      if (row == null) continue;
      var cached = images.get(spot.id());
      out.add(new Camera(spot.id(), spot.name(), cached != null ? Math.max(cached.image().takenAt(), row.takenAt()) : row.takenAt()));
    }
    return out;
  }

  /** The latest picture from a camera; the last good one for a while if its source is failing. */
  public Optional<Image> image(String id) {
    if (!enabled) return Optional.empty();
    var row = currentRow(id);
    if (row == null) return Optional.empty();
    var lock = imageLocks.computeIfAbsent(id, k -> new ReentrantLock());
    lock.lock();
    try {
      long now = clock.nowMs();
      var cached = images.get(id);
      if (cached != null && now - cached.fetchedAt() < IMAGE_EVERY) return Optional.of(cached.image());
      try {
        var image = fetchImage(row.url(), row.takenAt());
        images.put(id, new Cached(image, now));
        return Optional.of(image);
      } catch (Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        log.warn("Camera {} failed: {}", id, e.toString());
        if (cached != null && now - cached.fetchedAt() < KEEP_FAILED_FOR) return Optional.of(cached.image());
        return Optional.empty();
      }
    } finally {
      lock.unlock();
    }
  }

  private Listed currentRow(String id) {
    refreshList();
    return listed.get(id);
  }

  private void refreshList() {
    if (clock.nowMs() - listedAt < LIST_EVERY || !listLock.tryLock()) return;
    try {
      long now = clock.nowMs();
      if (now - listedAt < LIST_EVERY) return;
      listedAt = now; // a failure waits its turn too: the source isn't asked again and again
      try {
        listed = parseList(new String(get(listUri()), StandardCharsets.UTF_8), now);
      } catch (Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        log.warn("Camera list failed; keeping the last one: {}", e.toString());
      }
    } finally {
      listLock.unlock();
    }
  }

  private URI listUri() {
    String where = spots.stream().map(s -> "SnapShot LIKE '%" + s.match().replace("'", "''") + "%'").collect(Collectors.joining(" OR "));
    return URI.create(dataset + "?where=" + URLEncoder.encode(where, StandardCharsets.UTF_8)
        + "&returnGeometry=false&outFields=SnapShot,AgeInMinutes,TooOld&f=json");
  }

  Map<String, Listed> parseList(String body, long now) {
    var out = new HashMap<String, Listed>();
    for (var feature : Json.tree(body).path("features")) {
      var a = feature.path("attributes");
      String url = a.path("SnapShot").asString("");
      if (a.path("TooOld").asBoolean(false) || !allowed(url)) continue;
      long age = a.path("AgeInMinutes").asLong(0);
      for (var spot : spots) {
        if (url.contains(spot.match())) out.put(spot.id(), new Listed(url, now - age * 60_000L));
      }
    }
    return out;
  }

  /** The source's data decides the URL, so this is what keeps the server from being pointed anywhere else. */
  private boolean allowed(String url) {
    try {
      var u = URI.create(url);
      return imageScheme.equals(u.getScheme()) && imageHost.equals(u.getHost()) && u.getUserInfo() == null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private Image fetchImage(String url, long listedTakenAt) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", "Radiance/1.0").GET().build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (var body = response.body()) {
      String type = response.headers().firstValue("Content-Type").orElse("");
      if (response.statusCode() != 200 || !type.startsWith("image/jpeg")) {
        throw new IllegalStateException("HTTP " + response.statusCode() + " " + type);
      }
      byte[] bytes = body.readNBytes(MAX_IMAGE_BYTES + 1);
      if (bytes.length > MAX_IMAGE_BYTES) throw new IllegalStateException("image too large");
      long taken = response.headers().firstValue("Last-Modified").map(CameraFeed::parseDate).orElse(listedTakenAt);
      return new Image(bytes, taken);
    }
  }

  static long parseDate(String s) {
    try {
      return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
    } catch (DateTimeParseException e) {
      return 0;
    }
  }

  private byte[] get(URI uri) throws Exception {
    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("User-Agent", "Radiance/1.0").GET().build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (var body = response.body()) {
      if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
      byte[] bytes = body.readNBytes(MAX_LIST_BYTES + 1);
      if (bytes.length > MAX_LIST_BYTES) throw new IllegalStateException("list too large");
      return bytes;
    }
  }
}
