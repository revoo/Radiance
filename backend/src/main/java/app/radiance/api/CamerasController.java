package app.radiance.api;

import app.radiance.cameras.CameraFeed;
import app.radiance.kernel.WallClock;
import app.radiance.live.LivePlay;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Real Chicago, for the left rail (ADR 0020). Only while the world's clock agrees with the real one. */
@RestController
@RequestMapping("/api/cameras")
@ConditionalOnWebApplication
class CamerasController {

  /** Past this, a live picture would contradict the world's own time of day. */
  static final long MAX_DRIFT = 5 * 60_000L;

  private final CameraFeed feed;
  private final LivePlay live;
  private final WallClock clock;

  CamerasController(CameraFeed feed, LivePlay live, WallClock clock) {
    this.feed = feed;
    this.live = live;
    this.clock = clock;
  }

  static boolean inRealTime(long worldTime, long wall) {
    return Math.abs(worldTime - wall) <= MAX_DRIFT;
  }

  private boolean inRealTime() {
    return inRealTime(live.now(), clock.nowMs());
  }

  @GetMapping
  List<CameraFeed.Camera> cameras() {
    return inRealTime() ? feed.cameras() : List.of();
  }

  @GetMapping("/{id}/image")
  ResponseEntity<byte[]> image(@PathVariable String id) {
    if (!inRealTime()) return ResponseEntity.notFound().build();
    return feed.image(id)
        .map(i -> ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePrivate()).body(i.jpeg()))
        .orElse(ResponseEntity.notFound().build());
  }
}
