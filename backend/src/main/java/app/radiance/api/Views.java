package app.radiance.api;

import java.util.List;
import java.util.Map;

/** What the API returns (SPEC §7): only aired, rendered content, with counts that reflect it. */
final class Views {

  private Views() {}

  record Actor(String id, String handle, String name, boolean you, String occupation, String livesIn) {}

  /** Top three kinds, the total, and R's own reaction (null if none). */
  record Reactions(int total, List<String> top, Map<String, Integer> byKind, String mine, List<String> recent) {
    static final Reactions NONE = new Reactions(0, List.of(), Map.of(), null, List.of());
  }

  record Typing(String actorId, String name) {}

  record Comment(String id, String postId, String parentId, Actor author, long createdAt, String body, Reactions reactions,
      List<Comment> replies) {}

  record Post(String id, Actor author, long createdAt, String kind, String audience, String body, Reactions reactions,
      int commentCount, List<Comment> preview, List<Typing> typing) {}

  record Page<T>(List<T> items, String nextCursor) {}

  record Me(String username, boolean worldReady, boolean onboarded, Actor actor, String networkName) {}

  record World(String name, String networkName, String timezone, long worldTime, long committedUntil, long scriptedUntil,
      boolean paused, boolean backfilling) {}

  record RecapLine(String eventId, String line, int importance, long at, String postId, Actor actor) {}

  record Recap(Long since, List<RecapLine> lines) {}

  record Person(Actor actor, String bio, String employer, String hometown, String relationshipStatus, String birthday,
      String relationship, boolean friend, int mutualFriends, List<Actor> friends) {}

  record Notification(String id, String kind, List<Actor> actors, String refType, String refId, String postId,
      String snippet, long createdAt, boolean read) {}

  record Notifications(List<Notification> items, int unread) {}
}
