package app.radiance.sim;

import app.radiance.messenger.DmRow;
import app.radiance.sim.model.Intent;
import app.radiance.social.CommentRow;
import app.radiance.social.PostRow;
import app.radiance.social.ReactionRow;
import app.radiance.story.EventRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scripted rows — events, posts, comments, reactions and DMs — as one planning run produced them or as
 * airing loads them (SPEC §5.1). {@link #entries()} is the airing order: by time, then events before
 * the posts they cause, posts before their comments, comments before reactions, then DMs.
 */
public final class Script {

  public enum Type { EVENT, POST, COMMENT, REACTION, DM }

  /** One scripted item; exactly one of the row fields is set. */
  public record Entry(Type type, long time, String id, EventRow event, PostRow post, CommentRow comment,
      ReactionRow reaction, DmRow.Message dm) {}

  public final List<EventRow> events = new ArrayList<>();
  public final List<PostRow> posts = new ArrayList<>();
  public final List<CommentRow> comments = new ArrayList<>();
  public final List<ReactionRow> reactions = new ArrayList<>();
  public final List<DmRow.Message> dms = new ArrayList<>();
  public final List<DmRow.Thread> threads = new ArrayList<>();
  /** Intents planned past the end of this script (SPEC §5.1). */
  public final List<Intent> intents = new ArrayList<>();

  public static String reactionId(ReactionRow r) {
    return r.targetType() + ":" + r.targetId() + ":" + r.actorId();
  }

  public List<Entry> entries() {
    var out = new ArrayList<Entry>();
    events.forEach(e -> out.add(new Entry(Type.EVENT, e.worldTime(), e.id(), e, null, null, null, null)));
    posts.forEach(p -> out.add(new Entry(Type.POST, p.createdAt(), p.id(), null, p, null, null, null)));
    comments.forEach(c -> out.add(new Entry(Type.COMMENT, c.createdAt(), c.id(), null, null, c, null, null)));
    reactions.forEach(r -> out.add(new Entry(Type.REACTION, r.createdAt(), reactionId(r), null, null, null, r, null)));
    dms.forEach(d -> out.add(new Entry(Type.DM, d.createdAt(), d.id(), null, null, null, null, d)));
    out.sort(Comparator.comparingLong(Entry::time).thenComparing(Entry::type).thenComparing(Entry::id));
    return out;
  }

  public int size() {
    return events.size() + posts.size() + comments.size() + reactions.size() + dms.size();
  }

  public void addAll(Script other) {
    events.addAll(other.events);
    posts.addAll(other.posts);
    comments.addAll(other.comments);
    reactions.addAll(other.reactions);
    dms.addAll(other.dms);
    threads.addAll(other.threads);
    intents.addAll(other.intents);
  }
}
