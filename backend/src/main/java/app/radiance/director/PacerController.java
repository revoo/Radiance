package app.radiance.director;

import app.radiance.live.LivePlay;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A basic pacing monitor (SPEC §12 M7; the full one is the Director's console, M13): the session, the
 * typing indicators on screen, and the Pacer's state — per-minute counts against the limits, breathing
 * room, the last hook, and each response burst's timings.
 */
@RestController
class PacerController {

  private final LivePlay live;

  PacerController(LivePlay live) {
    this.live = live;
  }

  @GetMapping("/api/director/pacer")
  LivePlay.Monitor pacer() {
    return live.monitor();
  }
}
