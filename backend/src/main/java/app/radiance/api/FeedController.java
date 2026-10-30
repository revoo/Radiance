package app.radiance.api;

import app.radiance.live.LivePlay;
import app.radiance.persistence.Sql;
import app.radiance.social.ReactionRow;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** R's feed, posts, comments and reactions (SPEC §7). R's own writes are aired before the response returns. */
@ConditionalOnWebApplication
@RestController
@RequestMapping("/api")
class FeedController {

  record NewPost(String text, List<String> mediaIds, List<String> mentions) {}

  record NewComment(String text, String parentId) {}

  record NewReaction(String targetType, String targetId, String kind) {}

  static final int MAX_TEXT = 5_000;

  private final Sql sql;
  private final LivePlay live;

  FeedController(Sql sql, LivePlay live) {
    this.sql = sql;
    this.live = live;
  }

  @GetMapping("/feed")
  Views.Page<Views.Post> feed(@RequestParam(defaultValue = "recent") String mode, @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    var reads = new Reads(sql, live);
    int n = Math.max(1, Math.min(50, limit));
    return mode.equals("top") ? reads.top(cursor, n, live.now()) : reads.recent(cursor, n);
  }

  @PostMapping("/posts")
  ResponseEntity<Views.Post> post(@RequestBody NewPost body) {
    String text = text(body.text());
    requireOnboarded();
    String id = live.post(text);
    return ResponseEntity.status(HttpStatus.CREATED).body(new Reads(sql, live).post(id).orElseThrow());
  }

  @GetMapping("/posts/{id}")
  Views.Post get(@PathVariable String id) {
    return new Reads(sql, live).post(id).orElseThrow(() -> Problems.notFound("post"));
  }

  @GetMapping("/posts/{id}/comments")
  List<Views.Comment> comments(@PathVariable String id) {
    var reads = new Reads(sql, live);
    if (reads.post(id).isEmpty()) throw Problems.notFound("post");
    return reads.comments(id);
  }

  @PostMapping("/posts/{id}/comments")
  ResponseEntity<Views.Comment> comment(@PathVariable String id, @RequestBody NewComment body) {
    String text = text(body.text());
    requireOnboarded();
    if (new Reads(sql, live).post(id).isEmpty()) throw Problems.notFound("post");
    var commentId = live.comment(id, body.parentId(), text).orElseThrow(() -> Problems.notFound("post"));
    var made = new Reads(sql, live).comments(id).stream()
        .flatMap(c -> java.util.stream.Stream.concat(java.util.stream.Stream.of(c), c.replies().stream()))
        .filter(c -> c.id().equals(commentId)).findFirst().orElseThrow();
    return ResponseEntity.status(HttpStatus.CREATED).body(made);
  }

  @PutMapping("/reactions")
  ResponseEntity<Void> react(@RequestBody NewReaction body) {
    requireOnboarded();
    ReactionRow.Target type;
    ReactionRow.Kind kind;
    try {
      type = ReactionRow.Target.valueOf(body.targetType());
      kind = ReactionRow.Kind.valueOf(body.kind());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw Problems.bad("targetType is POST or COMMENT; kind is LIKE, LOVE, CARE, HAHA, WOW, SAD or ANGRY");
    }
    // R can only react to what he can see
    if (body.targetId() == null || !new Reads(sql, live).visible(type.name(), body.targetId())
        || !live.react(type, body.targetId(), kind)) {
      throw Problems.notFound(type.name().toLowerCase());
    }
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/reactions/{targetType}/{targetId}")
  ResponseEntity<Void> unreact(@PathVariable String targetType, @PathVariable String targetId) {
    ReactionRow.Target type;
    try {
      type = ReactionRow.Target.valueOf(targetType);
    } catch (IllegalArgumentException e) {
      throw Problems.bad("targetType is POST or COMMENT");
    }
    live.unreact(type, targetId);
    return ResponseEntity.noContent().build();
  }

  private void requireOnboarded() {
    if (new Reads(sql, live).userId() == null) {
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "finish onboarding first");
    }
  }

  private static String text(String t) {
    if (t == null || t.isBlank()) throw Problems.bad("text is required");
    if (t.length() > MAX_TEXT) throw Problems.bad("text is longer than " + MAX_TEXT + " characters");
    return t.strip();
  }
}
