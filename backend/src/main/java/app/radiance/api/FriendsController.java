package app.radiance.api;

import app.radiance.live.LivePlay;
import app.radiance.persistence.Sql;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Friend requests (SPEC §5.9, §7): R's are decided within seconds; residents' wait for R's answer. */
@RestController
@RequestMapping("/api")
@ConditionalOnWebApplication
class FriendsController {

  private final Sql sql;
  private final LivePlay live;

  FriendsController(Sql sql, LivePlay live) {
    this.sql = sql;
    this.live = live;
  }

  @GetMapping("/friend-requests")
  List<Views.FriendRequest> incoming() {
    return new Reads(sql, live).friendRequests();
  }

  @PostMapping("/people/{handle}/friend-request")
  ResponseEntity<Void> send(@PathVariable String handle) {
    var id = new Reads(sql, live).actorIdByHandle(handle);
    if (id.isEmpty()) return ResponseEntity.notFound().build();
    return live.requestFriend(id.get()).isPresent() ? ResponseEntity.noContent().build() : ResponseEntity.status(409).build();
  }

  @PostMapping("/friend-requests/{id}/accept")
  ResponseEntity<Void> accept(@PathVariable String id) {
    return live.answerFriendRequest(id, true) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
  }

  @PostMapping("/friend-requests/{id}/decline")
  ResponseEntity<Void> decline(@PathVariable String id) {
    return live.answerFriendRequest(id, false) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
  }
}
