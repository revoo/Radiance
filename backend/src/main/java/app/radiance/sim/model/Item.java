package app.radiance.sim.model;

import app.radiance.kernel.AirStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A recent post, comment or DM as the engagement model and airing see it: who wrote it, what it
 * responds to, and the structure its brief carries (category, stance, topics, controversy, drama).
 */
public final class Item {

  public enum Type { POST, COMMENT, DM }

  public final String id;
  public final Type type;
  public final String authorId;
  /** The post a comment belongs to; null for posts and DMs. */
  public final String postId;
  /** The comment a reply answers. */
  public final String parentId;
  /** Who a comment or DM addresses: the parent's author, the post's author, the DM's recipient. */
  public final String toward;
  public final long time;
  public AirStatus status;
  /** Post kind (STATUS, PHOTO…), or COMMENT / REPLY / DM. */
  public final String kind;
  public final String category;
  public final String defId;
  public final String stance;
  public final List<String> topics;
  public final double controversy;
  public final double drama;
  public final int importance;
  public final String arcId;
  public final String eventId;
  public final String headlineId;
  public final boolean publicAudience;
  /** −1 bad news … +1 good news, from the brief: drives CONGRATS, CONDOLENCE and reaction kinds. */
  public double valence;
  /** For news takes: −1…1, how far the author's stance leans right/conservative. */
  public double lean;
  public boolean tragic;
  public int commentCount;
  public final TreeSet<String> reactors = new TreeSet<>();
  public final List<String> commenters = new ArrayList<>();
  public final TreeMap<String, Integer> reactionCounts = new TreeMap<>();

  public Item(String id, Type type, String authorId, String postId, String parentId, String toward, long time,
      AirStatus status, String kind, String category, String defId, String stance, List<String> topics,
      double controversy, double drama, int importance, String arcId, String eventId, String headlineId,
      boolean publicAudience) {
    this.id = id;
    this.type = type;
    this.authorId = authorId;
    this.postId = postId;
    this.parentId = parentId;
    this.toward = toward;
    this.time = time;
    this.status = status;
    this.kind = kind;
    this.category = category;
    this.defId = defId;
    this.stance = stance;
    this.topics = topics == null ? List.of() : List.copyOf(topics);
    this.controversy = controversy;
    this.drama = drama;
    this.importance = importance;
    this.arcId = arcId;
    this.eventId = eventId;
    this.headlineId = headlineId;
    this.publicAudience = publicAudience;
  }

  public Item copy() {
    var i = new Item(id, type, authorId, postId, parentId, toward, time, status, kind, category, defId, stance, topics,
        controversy, drama, importance, arcId, eventId, headlineId, publicAudience);
    i.valence = valence;
    i.lean = lean;
    i.tragic = tragic;
    i.commentCount = commentCount;
    i.reactors.addAll(reactors);
    i.commenters.addAll(commenters);
    i.reactionCounts.putAll(reactionCounts);
    return i;
  }

  public boolean aired() {
    return status == AirStatus.AIRED;
  }
}
