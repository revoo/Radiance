package app.radiance.api;

import app.radiance.live.LivePlay;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.world.WorldProperties;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Who R is, his session, the world, the recap, people and notifications (SPEC §7). */
@RestController
@RequestMapping("/api")
class WorldController {

  private final Sql sql;
  private final WriterLane lane;
  private final LivePlay live;
  private final WorldProperties world;

  WorldController(Sql sql, WriterLane lane, LivePlay live, WorldProperties world) {
    this.sql = sql;
    this.lane = lane;
    this.live = live;
    this.world = world;
  }

  @GetMapping("/me")
  Views.Me me(Principal principal) {
    var reads = new Reads(sql, live);
    boolean residents = sql.queryOne("SELECT COUNT(*) AS n FROM actor WHERE kind = 'BOT'", List.of(), r -> r.lng("n"))
        .orElse(0L) > 0;
    String user = reads.userId();
    return new Views.Me(principal.getName(), residents, user != null, user == null ? null : reads.actor(user),
        world.networkName());
  }

  @PostMapping("/session/heartbeat")
  LivePlay.Status heartbeat() {
    return live.heartbeat();
  }

  @GetMapping("/world")
  ResponseEntity<Views.World> world() {
    var status = live.status();
    return sql.queryOne("SELECT name, network_name, timezone, committed_until, scripted_until, paused FROM world", List.of(),
            r -> new Views.World(r.str("name"), r.str("network_name"), r.str("timezone"), status.worldTime(),
                r.lng("committed_until"), r.lng("scripted_until"), r.bool("paused"), status.backfilling()))
        .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
  }

  @GetMapping("/world/recap")
  Views.Recap recap() {
    return new Reads(sql, live).recap(live.now());
  }

  @GetMapping("/people")
  List<Views.Actor> people(@RequestParam(defaultValue = "all") String filter) {
    return new Reads(sql, live).people(filter);
  }

  @GetMapping("/people/{handle}")
  Views.Person person(@PathVariable String handle) {
    return new Reads(sql, live).person(handle).orElseThrow(() -> Problems.notFound("person"));
  }

  @GetMapping("/people/{handle}/posts")
  Views.Page<Views.Post> posts(@PathVariable String handle, @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    var reads = new Reads(sql, live);
    String id = reads.actorIdByHandle(handle).orElseThrow(() -> Problems.notFound("person"));
    return reads.postsBy(id, cursor, Math.max(1, Math.min(50, limit)));
  }

  @GetMapping("/notifications")
  Views.Notifications notifications(@RequestParam(defaultValue = "30") int limit) {
    return new Reads(sql, live).notifications(Math.max(1, Math.min(100, limit)));
  }

  @PostMapping("/notifications/read")
  ResponseEntity<Void> read() {
    long now = live.now();
    lane.run(() -> sql.commit(new UnitOfWork().add("UPDATE notification SET read_at = ? WHERE read_at IS NULL", now)));
    return ResponseEntity.noContent().build();
  }
}
