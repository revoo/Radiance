package app.radiance.api;

import app.radiance.live.LivePlay;
import app.radiance.messenger.DmRepository;
import app.radiance.messenger.DmRow;
import app.radiance.persistence.Sql;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Messenger (SPEC §5.11, §7): R's threads with residents, their messages (only aired, rendered ones),
 * sending, and reading. Replies arrive over the stream as {@code dm.message}, after {@code typing.*}.
 */
@ConditionalOnWebApplication
@RestController
@RequestMapping("/api/dm/threads")
class DmController {

  record NewThread(String handle) {}

  record NewMessage(String text) {}

  static final int MAX_TEXT = 2_000;

  private final Sql sql;
  private final LivePlay live;

  DmController(Sql sql, LivePlay live) {
    this.sql = sql;
    this.live = live;
  }

  @GetMapping
  List<Views.DmThread> threads() {
    var reads = new Reads(sql, live);
    String me = reads.userId();
    if (me == null) return List.of();
    var rows = new ArrayList<DmRow.Thread>();
    // two indexed lookups rather than an OR across columns (SPEC §4.4)
    rows.addAll(sql.query("SELECT id, a_id, b_id, last_message_at FROM dm_thread WHERE a_id = ?", List.of(me), DmController::row));
    rows.addAll(sql.query("SELECT id, a_id, b_id, last_message_at FROM dm_thread WHERE b_id = ?", List.of(me), DmController::row));
    var out = new ArrayList<Views.DmThread>();
    for (var t : rows) {
      var view = thread(reads, t);
      if (view.lastMessage() != null || !live.typingIn(t.id()).isEmpty()) out.add(view);
    }
    out.sort(Comparator.comparing((Views.DmThread v) -> v.lastMessage() == null ? Long.MAX_VALUE : -v.lastMessage().createdAt()));
    return out;
  }

  @PostMapping
  ResponseEntity<Views.DmThread> open(@RequestBody NewThread body) {
    var reads = new Reads(sql, live);
    if (reads.userId() == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "finish onboarding first");
    String resident = body.handle() == null ? null : reads.actorIdByHandle(body.handle()).orElse(null);
    if (resident == null || resident.equals(reads.userId())) throw Problems.notFound("person");
    String id = live.openThread(resident);
    var t = new DmRepository(sql).thread(id).orElseThrow();
    return ResponseEntity.ok(thread(reads, t));
  }

  @GetMapping("/{id}")
  Views.DmThread get(@PathVariable String id) {
    var reads = new Reads(sql, live);
    return thread(reads, mine(reads, id));
  }

  @GetMapping("/{id}/messages")
  Views.DmConversation messages(@PathVariable String id) {
    var reads = new Reads(sql, live);
    var t = mine(reads, id);
    var messages = sql.query("SELECT id, sender_id, body, created_at, read_at FROM dm_message WHERE thread_id = ?"
        + " AND air_status = 'AIRED' AND body IS NOT NULL ORDER BY created_at, id", List.of(id),
        r -> new Views.DmMessage(r.str("id"), id, r.str("sender_id"), r.str("sender_id").equals(reads.userId()), r.str("body"),
            r.lng("created_at"), r.lngOrNull("read_at")));
    var typing = live.typingIn(id).stream().map(x -> new Views.Typing(x.actorId(), x.name())).toList();
    return new Views.DmConversation(thread(reads, t), messages, typing);
  }

  @PostMapping("/{id}/messages")
  ResponseEntity<Views.DmMessage> send(@PathVariable String id, @RequestBody NewMessage body) {
    var reads = new Reads(sql, live);
    mine(reads, id);
    String text = body.text() == null ? "" : body.text().strip();
    if (text.isEmpty()) throw Problems.bad("text is required");
    if (text.length() > MAX_TEXT) throw Problems.bad("text is longer than " + MAX_TEXT + " characters");
    String messageId = live.dm(id, text).orElseThrow(() -> Problems.notFound("thread"));
    var made = sql.queryOne("SELECT created_at FROM dm_message WHERE id = ?", List.of(messageId), r -> r.lng("created_at"))
        .orElseThrow();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new Views.DmMessage(messageId, id, reads.userId(), true, text, made, null));
  }

  @PostMapping("/{id}/read")
  ResponseEntity<Void> read(@PathVariable String id) {
    var reads = new Reads(sql, live);
    mine(reads, id);
    live.readThread(id);
    return ResponseEntity.noContent().build();
  }

  private DmRow.Thread mine(Reads reads, String id) {
    var t = new DmRepository(sql).thread(id).orElse(null);
    String me = reads.userId();
    if (t == null || me == null || !(me.equals(t.aId()) || me.equals(t.bId()))) throw Problems.notFound("thread");
    return t;
  }

  private Views.DmThread thread(Reads reads, DmRow.Thread t) {
    String me = reads.userId();
    String other = me.equals(t.aId()) ? t.bId() : t.aId();
    var last = sql.queryOne("SELECT id, sender_id, body, created_at, read_at FROM dm_message WHERE thread_id = ?"
        + " AND air_status = 'AIRED' AND body IS NOT NULL ORDER BY created_at DESC, id DESC LIMIT 1", List.of(t.id()),
        r -> new Views.DmMessage(r.str("id"), t.id(), r.str("sender_id"), r.str("sender_id").equals(me), r.str("body"),
            r.lng("created_at"), r.lngOrNull("read_at"))).orElse(null);
    int unread = sql.queryOne("SELECT COUNT(*) AS n FROM dm_message WHERE thread_id = ? AND sender_id = ?"
        + " AND air_status = 'AIRED' AND body IS NOT NULL AND read_at IS NULL", List.of(t.id(), other), r -> r.lng("n"))
        .orElse(0L).intValue();
    return new Views.DmThread(t.id(), reads.actor(other), last, unread, !live.typingIn(t.id()).isEmpty());
  }

  private static DmRow.Thread row(app.radiance.persistence.Row r) {
    return new DmRow.Thread(r.str("id"), r.str("a_id"), r.str("b_id"), null, r.lngOrNull("last_message_at"));
  }
}
