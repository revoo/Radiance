package app.radiance.api;

import app.radiance.live.LivePlay;
import app.radiance.persistence.Sql;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The read side of the API (SPEC §7, §5.8): R's feed, posts and comments, people, notifications and
 * the recap, straight from the database. Only aired, rendered content; nothing waits on simulation.
 */
final class Reads {

  private static final long DAY = 86_400_000L;
  private static final String VISIBLE = "air_status = 'AIRED' AND render_status = 'RENDERED' AND body IS NOT NULL";

  private final Sql sql;
  private final LivePlay live;
  private final Map<String, Views.Actor> actors = new HashMap<>();
  private final String userId;

  Reads(Sql sql, LivePlay live) {
    this.sql = sql;
    this.live = live;
    String user = null;
    for (var a : sql.query("SELECT id, kind, handle, display_name, occupation, current_city FROM actor", List.of(),
        r -> new String[] {r.str("id"), r.str("kind"), r.str("handle"), r.str("display_name"), r.str("occupation"),
            r.str("current_city")})) {
      boolean you = a[1].equals("USER");
      if (you) user = a[0];
      actors.put(a[0], new Views.Actor(a[0], a[2], a[3], you, a[4], a[5]));
    }
    this.userId = user;
  }

  String userId() {
    return userId;
  }

  Views.Actor actor(String id) {
    return actors.getOrDefault(id, new Views.Actor(id, "unknown", "Someone", false, null, null));
  }

  Optional<String> actorIdByHandle(String handle) {
    return actors.values().stream().filter(a -> a.handle().equals(handle)).map(Views.Actor::id).findFirst();
  }

  /** The residents R is connected to. */
  Set<String> friends() {
    if (userId == null) return Set.of();
    return new LinkedHashSet<>(sql.query("SELECT to_id FROM relationship WHERE from_id = ? AND connected = 1",
        List.of(userId), r -> r.str("to_id")));
  }

  // ------------------------------------------------------------------------------------------- feed

  /** "Recent": reverse chronological, friends and R only; the cursor is the last post's id. */
  Views.Page<Views.Post> recent(String cursor, int limit) {
    var authors = new ArrayList<>(friends());
    if (userId != null) authors.add(userId);
    if (authors.isEmpty()) return new Views.Page<>(List.of(), null);
    var params = new ArrayList<Object>(authors);
    String where = "author_id IN (" + marks(authors.size()) + ") AND " + VISIBLE;
    if (cursor != null) {
      var at = sql.queryOne("SELECT created_at FROM post WHERE id = ?", List.of(cursor), r -> r.lng("created_at"));
      if (at.isPresent()) {
        where += " AND (created_at < ? OR (created_at = ? AND id < ?))";
        params.addAll(List.of(at.get(), at.get(), cursor));
      }
    }
    params.add(limit + 1);
    var rows = sql.query("SELECT id, author_id, created_at, kind, audience, body, comment_count FROM post WHERE " + where
        + " ORDER BY created_at DESC, id DESC LIMIT ?", params, Reads::postRow);
    boolean more = rows.size() > limit;
    var page = more ? rows.subList(0, limit) : rows;
    return new Views.Page<>(posts(page), more ? page.getLast()[0] : null);
  }

  /** "Top": recency decay × closeness, plus engagement, over the last three days; the cursor is an offset. */
  Views.Page<Views.Post> top(String cursor, int limit, long now) {
    var authors = new ArrayList<>(friends());
    if (userId != null) authors.add(userId);
    if (authors.isEmpty()) return new Views.Page<>(List.of(), null);
    var params = new ArrayList<Object>(authors);
    params.add(now - 3 * DAY);
    var rows = sql.query("SELECT id, author_id, created_at, kind, audience, body, comment_count FROM post WHERE author_id IN ("
        + marks(authors.size()) + ") AND " + VISIBLE + " AND created_at > ? ORDER BY created_at DESC LIMIT 300", params,
        Reads::postRow);
    var familiarity = new HashMap<String, Double>();
    if (userId != null) {
      sql.query("SELECT to_id, familiarity FROM relationship WHERE from_id = ?", List.of(userId),
          r -> Map.entry(r.str("to_id"), r.dbl("familiarity"))).forEach(e -> familiarity.put(e.getKey(), e.getValue()));
    }
    var reactions = reactionTotals(rows.stream().map(r -> r[0]).toList());
    var scored = new ArrayList<Map.Entry<String[], Double>>();
    for (var r : rows) {
      double ageH = Math.max(0, (now - Long.parseLong(r[2])) / 3_600_000.0);
      double closeness = r[1].equals(userId) ? 1.0 : 0.3 + familiarity.getOrDefault(r[1], 0.0) / 100;
      double engagement = Math.log1p(reactions.getOrDefault(r[0], 0) + 2.0 * Integer.parseInt(r[6]));
      scored.add(Map.entry(r, Math.exp(-ageH / 18) * closeness + 0.25 * engagement));
    }
    scored.sort(Comparator.comparing((Map.Entry<String[], Double> e) -> -e.getValue()).thenComparing(e -> e.getKey()[0]));
    int from = cursor == null ? 0 : Math.max(0, Integer.parseInt(cursor));
    int to = Math.min(scored.size(), from + limit);
    var page = from >= to ? List.<String[]>of() : scored.subList(from, to).stream().map(Map.Entry::getKey).toList();
    return new Views.Page<>(posts(page), to < scored.size() ? String.valueOf(to) : null);
  }

  /** A person's posts that R can see: everything a friend posts, or what anyone posts publicly. */
  Views.Page<Views.Post> postsBy(String authorId, String cursor, int limit) {
    boolean friend = authorId.equals(userId) || friends().contains(authorId);
    var params = new ArrayList<Object>(List.of(authorId));
    String where = "author_id = ? AND " + VISIBLE + (friend ? "" : " AND audience = 'PUBLIC'");
    if (cursor != null) {
      var at = sql.queryOne("SELECT created_at FROM post WHERE id = ?", List.of(cursor), r -> r.lng("created_at"));
      if (at.isPresent()) {
        where += " AND (created_at < ? OR (created_at = ? AND id < ?))";
        params.addAll(List.of(at.get(), at.get(), cursor));
      }
    }
    params.add(limit + 1);
    var rows = sql.query("SELECT id, author_id, created_at, kind, audience, body, comment_count FROM post WHERE " + where
        + " ORDER BY created_at DESC, id DESC LIMIT ?", params, Reads::postRow);
    boolean more = rows.size() > limit;
    var page = more ? rows.subList(0, limit) : rows;
    return new Views.Page<>(posts(page), more ? page.getLast()[0] : null);
  }

  Optional<Views.Post> post(String id) {
    var row = sql.queryOne("SELECT id, author_id, created_at, kind, audience, body, comment_count FROM post WHERE id = ? AND "
        + VISIBLE, List.of(id), Reads::postRow);
    if (row.isEmpty()) return Optional.empty();
    var r = row.get();
    if (!(r[1].equals(userId) || r[4].equals("PUBLIC") || friends().contains(r[1]))) return Optional.empty();
    return Optional.of(posts(List.<String[]>of(r)).getFirst());
  }

  /** Whether R can see this post, or the post this comment is on. */
  boolean visible(String targetType, String id) {
    if (targetType.equals("POST")) return post(id).isPresent();
    return sql.queryOne("SELECT post_id FROM comment WHERE id = ? AND " + VISIBLE, List.of(id), r -> r.str("post_id"))
        .map(p -> post(p).isPresent()).orElse(false);
  }

  /** Every aired, rendered comment on a post, one level of replies (deeper replies join their thread). */
  List<Views.Comment> comments(String postId) {
    var rows = sql.query("SELECT id, parent_id, author_id, created_at, body FROM comment WHERE post_id = ? AND " + VISIBLE
        + " ORDER BY created_at, id", List.of(postId), r -> new String[] {r.str("id"), r.str("parent_id"), r.str("author_id"),
            String.valueOf(r.lng("created_at")), r.str("body")});
    var reactions = reactions("COMMENT", rows.stream().map(r -> r[0]).toList());
    var top = new LinkedHashMap<String, List<Views.Comment>>();
    var parentOf = new HashMap<String, String>();
    var byId = new HashMap<String, String[]>();
    for (var r : rows) byId.put(r[0], r);
    for (var r : rows) {
      String root = r[1];
      while (root != null && byId.containsKey(root) && byId.get(root)[1] != null) root = byId.get(root)[1];
      if (root == null || !byId.containsKey(root)) top.put(r[0], new ArrayList<>());
      else parentOf.put(r[0], root);
    }
    var out = new ArrayList<Views.Comment>();
    for (var r : rows) {
      String root = parentOf.get(r[0]);
      if (root != null && top.containsKey(root)) top.get(root).add(comment(r, postId, reactions, List.of()));
    }
    for (var e : top.entrySet()) out.add(comment(byId.get(e.getKey()), postId, reactions, e.getValue()));
    return out;
  }

  private Views.Comment comment(String[] r, String postId, Map<String, Views.Reactions> reactions, List<Views.Comment> replies) {
    return new Views.Comment(r[0], postId, r[1], actor(r[2]), Long.parseLong(r[3]), r[4],
        reactions.getOrDefault(r[0], Views.Reactions.NONE), replies);
  }

  private List<Views.Post> posts(List<String[]> rows) {
    var ids = rows.stream().map(r -> r[0]).toList();
    var reactions = reactions("POST", ids);
    var out = new ArrayList<Views.Post>();
    for (var r : rows) {
      var preview = sql.query("SELECT id, parent_id, author_id, created_at, body FROM comment WHERE post_id = ? AND parent_id IS NULL AND "
          + VISIBLE + " ORDER BY created_at DESC, id DESC LIMIT 2", List.of(r[0]), x -> new String[] {x.str("id"),
              x.str("parent_id"), x.str("author_id"), String.valueOf(x.lng("created_at")), x.str("body")});
      Collections.reverse(preview);
      var previewReactions = reactions("COMMENT", preview.stream().map(x -> x[0]).toList());
      var typing = live == null ? List.<Views.Typing>of()
          : live.typingOn(r[0]).stream().map(t -> new Views.Typing(t.actorId(), t.name())).toList();
      out.add(new Views.Post(r[0], actor(r[1]), Long.parseLong(r[2]), r[3], r[4], r[5],
          reactions.getOrDefault(r[0], Views.Reactions.NONE), Integer.parseInt(r[6]),
          preview.stream().map(x -> comment(x, r[0], previewReactions, List.of())).toList(), typing));
    }
    return out;
  }

  private static String[] postRow(app.radiance.persistence.Row r) {
    return new String[] {r.str("id"), r.str("author_id"), String.valueOf(r.lng("created_at")), r.str("kind"),
        r.str("audience"), r.str("body"), String.valueOf(r.lng("comment_count"))};
  }

  // ------------------------------------------------------------------------------------- reactions

  private Map<String, Integer> reactionTotals(List<String> postIds) {
    var out = new HashMap<String, Integer>();
    reactions("POST", postIds).forEach((k, v) -> out.put(k, v.total()));
    return out;
  }

  Map<String, Views.Reactions> reactions(String targetType, List<String> ids) {
    var rows = new ArrayList<String[]>();
    for (int i = 0; i < ids.size(); i += 80) {
      var chunk = ids.subList(i, Math.min(ids.size(), i + 80));
      var params = new ArrayList<Object>(List.of(targetType));
      params.addAll(chunk);
      rows.addAll(sql.query("SELECT target_id, kind, actor_id FROM reaction WHERE target_type = ? AND target_id IN ("
          + marks(chunk.size()) + ") AND air_status = 'AIRED' ORDER BY created_at DESC", params,
          r -> new String[] {r.str("target_id"), r.str("kind"), r.str("actor_id")}));
    }
    var grouped = new LinkedHashMap<String, List<String[]>>();
    for (var r : rows) grouped.computeIfAbsent(r[0], k -> new ArrayList<>()).add(r);
    var out = new HashMap<String, Views.Reactions>();
    grouped.forEach((id, rs) -> {
      var byKind = new TreeMap<String, Integer>();
      String mine = null;
      var recent = new ArrayList<String>();
      for (var r : rs) {
        byKind.merge(r[1], 1, Integer::sum);
        if (r[2].equals(userId)) mine = r[1];
        else if (recent.size() < 3) recent.add(actor(r[2]).name());
      }
      var top = byKind.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
          .thenComparing(Map.Entry.comparingByKey())).limit(3).map(Map.Entry::getKey).toList();
      out.put(id, new Views.Reactions(rs.size(), top, byKind, mine, recent));
    });
    return out;
  }

  // ---------------------------------------------------------------------------------------- people

  List<Views.Actor> people(String filter) {
    var friends = friends();
    var you = userId == null ? null : actors.get(userId);
    var myCircles = new LinkedHashSet<String>();
    if (you != null) {
      sql.queryOne("SELECT circles_json FROM actor WHERE id = ?", List.of(userId), r -> r.str("circles_json"))
          .ifPresent(j -> app.radiance.kernel.Json.tree(j).forEach(n -> myCircles.add(n.asString())));
    }
    var out = new ArrayList<Views.Actor>();
    for (var a : actors.values().stream().sorted(Comparator.comparing(Views.Actor::name)).toList()) {
      if (a.you()) continue;
      boolean friend = friends.contains(a.id());
      switch (filter) {
        case "friends" -> {
          if (friend) out.add(a);
        }
        case "suggested" -> {
          if (friend) continue;
          var circles = sql.queryOne("SELECT circles_json FROM actor WHERE id = ?", List.of(a.id()), r -> r.str("circles_json"))
              .orElse("[]");
          for (var n : app.radiance.kernel.Json.tree(circles)) {
            if (myCircles.contains(n.asString())) {
              out.add(a);
              break;
            }
          }
        }
        default -> out.add(a);
      }
    }
    return out;
  }

  Optional<Views.Person> person(String handle) {
    var id = actorIdByHandle(handle);
    if (id.isEmpty()) return Optional.empty();
    var row = sql.queryOne("SELECT bio, employer, hometown, relationship_status, birth_date FROM actor WHERE id = ?",
        List.of(id.get()), r -> new String[] {r.str("bio"), r.str("employer"), r.str("hometown"),
            r.str("relationship_status"), r.str("birth_date")}).orElseThrow();
    var theirFriends = new LinkedHashSet<>(sql.query("SELECT to_id FROM relationship WHERE from_id = ? AND connected = 1",
        List.of(id.get()), r -> r.str("to_id")));
    var mine = friends();
    var kind = userId == null || id.get().equals(userId) ? null
        : sql.queryOne("SELECT kind FROM relationship WHERE from_id = ? AND to_id = ?", List.of(userId, id.get()),
            r -> r.str("kind")).orElse(null);
    int mutual = (int) theirFriends.stream().filter(mine::contains).count();
    String birthday = row[4] == null || row[4].length() < 10 ? null : row[4].substring(5);
    return Optional.of(new Views.Person(actor(id.get()), row[0], row[1], row[2], row[3], birthday, kind,
        mine.contains(id.get()), mutual, theirFriends.stream().map(this::actor).toList()));
  }

  // --------------------------------------------------------------------------------- notifications

  /** R's notifications, newest first; reactions on the same thing are one entry ("Sarah and 3 others"). */
  Views.Notifications notifications(int limit) {
    var rows = sql.query("SELECT id, kind, actor_id, ref_type, ref_id, created_at, read_at FROM notification"
        + " ORDER BY created_at DESC LIMIT ?", List.of(limit * 3), r -> new Object[] {r.str("id"), r.str("kind"),
            r.str("actor_id"), r.str("ref_type"), r.str("ref_id"), r.lng("created_at"), r.lngOrNull("read_at")});
    var out = new ArrayList<Views.Notification>();
    var grouped = new LinkedHashMap<String, List<Object[]>>();
    for (var r : rows) {
      String key = "REACTION".equals(r[1]) ? "R:" + r[4] : "N:" + r[0];
      grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
    }
    for (var g : grouped.values()) {
      if (out.size() >= limit) break;
      var first = g.getFirst();
      var actorIds = new LinkedHashSet<String>();
      boolean read = true;
      for (var r : g) {
        if (r[2] != null) actorIds.add((String) r[2]);
        if (r[6] == null) read = false;
      }
      String refType = (String) first[3], refId = (String) first[4];
      String postId = refId;
      String snippet = null;
      if ("COMMENT".equals(refType)) {
        var c = sql.queryOne("SELECT post_id, body FROM comment WHERE id = ?", List.of(refId),
            r -> new String[] {r.str("post_id"), r.str("body")});
        postId = c.map(x -> x[0]).orElse(null);
        snippet = c.map(x -> x[1]).orElse(null);
      } else if ("POST".equals(refType) && "MENTION".equals(first[1])) {
        snippet = sql.queryOne("SELECT body FROM post WHERE id = ?", List.of(refId), r -> r.str("body")).orElse(null);
      } else if (!"POST".equals(refType)) {
        postId = null;
      }
      if (snippet != null && snippet.length() > 90) snippet = snippet.substring(0, 87) + "…";
      out.add(new Views.Notification((String) first[0], (String) first[1], actorIds.stream().map(this::actor).toList(),
          refType, refId, postId, snippet, (long) first[5], read));
    }
    int unread = sql.queryOne("SELECT COUNT(*) AS n FROM notification WHERE read_at IS NULL", List.of(), r -> r.lng("n"))
        .orElse(0L).intValue();
    return new Views.Notifications(out, unread);
  }

  // ------------------------------------------------------------------------------------------ recap

  /**
   * "While you were away" (SPEC §5.14): the top lines among events that aired since R's last session,
   * by importance and then recency. On his first session, the last three days.
   */
  Views.Recap recap(long now) {
    var sessions = sql.query("SELECT started_at, ended_at FROM session_log ORDER BY started_at DESC LIMIT 2", List.of(),
        r -> r.lngOrNull("ended_at"));
    Long since = sessions.size() >= 2 ? sessions.get(1) : null;
    long from = since == null ? now - 3 * DAY : since;
    var lines = new ArrayList<Views.RecapLine>();
    for (var r : sql.query("SELECT id, recap_line, importance, world_time, actor_id FROM event WHERE air_status = 'AIRED'"
        + " AND importance >= 6 AND recap_line IS NOT NULL AND world_time > ? AND world_time <= ?"
        + " ORDER BY importance DESC, world_time DESC LIMIT 8", List.of(from, now), r -> new Object[] {r.str("id"),
            r.str("recap_line"), r.lng("importance"), r.lng("world_time"), r.str("actor_id")})) {
      String postId = sql.queryOne("SELECT id FROM post WHERE event_id = ? AND " + VISIBLE + " LIMIT 1", List.of(r[0]),
          x -> x.str("id")).orElse(null);
      lines.add(new Views.RecapLine((String) r[0], (String) r[1], ((Long) r[2]).intValue(), (long) r[3], postId,
          r[4] == null ? null : actor((String) r[4])));
    }
    return new Views.Recap(since, lines);
  }

  static String marks(int n) {
    return String.join(", ", Collections.nCopies(n, "?"));
  }
}
