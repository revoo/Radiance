package app.radiance.sim;

import app.radiance.content.ContentLibrary;
import app.radiance.kernel.AirStatus;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.RenderStatus;
import app.radiance.kernel.Ulid;
import app.radiance.kernel.Visibility;
import app.radiance.messenger.DmRow;
import app.radiance.news.Headline;
import app.radiance.sim.model.Edge;
import app.radiance.sim.model.SimWorld;
import app.radiance.social.CommentRow;
import app.radiance.social.PostRow;
import app.radiance.social.ReactionRow;
import app.radiance.story.EventRow;
import app.radiance.weather.WeatherBook;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One slice being planned (SPEC §5.1): the planning copy of the world, the slice, its inputs and the
 * script it's writing. Row builders give every item an id derived from the world seed and a stable key,
 * so the same inputs always give the same script.
 */
final class Plan {

  /** What a planning run needs besides the world: the batch's headlines and weather, R's prime hours. */
  record Inputs(List<Headline> headlines, WeatherBook weather, Set<Integer> primeHours, Map<String, Double> scales,
      ContentOrigin origin) {}

  final SimWorld w;
  final ContentLibrary lib;
  final Knobs k;
  final Slice slice;
  final Inputs in;
  final Script script = new Script();
  final RandomSource rng;
  /** Per-run counters that don't need persisting: R's share of each resident's attention today. */
  final Map<String, Integer> userInteractions;
  final Set<String> threadsMade = new HashSet<>();

  Plan(SimWorld w, ContentLibrary lib, Knobs k, Slice slice, Inputs in, Map<String, Integer> userInteractions) {
    this.w = w;
    this.lib = lib;
    this.k = k;
    this.slice = slice;
    this.in = in;
    this.rng = RandomSource.forSlice(w.seed, slice.start());
    this.userInteractions = userInteractions;
  }

  boolean structureOnly() {
    return slice.fidelity() == null;
  }

  String id(String kind, long t, String key) {
    return Ulid.next(t, RandomSource.seeded(w.seed).derive(kind + ":" + key));
  }

  EventRow event(String key, long t, String type, String defId, String actor, String target, String arcId,
      int importance, Visibility vis, EventRow.Source source, EventPayload payload) {
    var e = new EventRow(id("event", t, key), t, type, defId, actor, target, arcId, importance, vis, source,
        AirStatus.SCRIPTED, payload.json(), null);
    script.events.add(e);
    return e;
  }

  PostRow post(String key, long t, String author, PostRow.Kind kind, Brief brief, String arcId, String eventId,
      String headlineId, int importance) {
    var p = new PostRow(id("post", t, key), author, t, kind, PostRow.Audience.FRIENDS, in.origin, AirStatus.SCRIPTED,
        null, RenderStatus.PLANNED, brief.json(), null, null, headlineId, arcId, eventId, importance,
        fidelity(), "{}", 0);
    script.posts.add(p);
    return p;
  }

  CommentRow comment(String key, long t, String postId, String parentId, String author, Brief brief) {
    var c = new CommentRow(id("comment", t, key), postId, parentId, author, t, in.origin, AirStatus.SCRIPTED, null,
        null, RenderStatus.PLANNED, brief.json(), null, "{}");
    script.comments.add(c);
    return c;
  }

  ReactionRow reaction(long t, ReactionRow.Target type, String targetId, String actor, ReactionRow.Kind kind) {
    var r = new ReactionRow(type, targetId, actor, kind, t, AirStatus.SCRIPTED);
    script.reactions.add(r);
    return r;
  }

  DmRow.Message dm(String key, long t, String from, String to, Brief brief) {
    String a = from.compareTo(to) < 0 ? from : to, b = a.equals(from) ? to : from;
    String thread = id("thread", 0, Edge.pair(a, b));
    if (threadsMade.add(thread)) script.threads.add(new DmRow.Thread(thread, a, b, null, null));
    var m = new DmRow.Message(id("dm", t, key), thread, from, t, AirStatus.SCRIPTED, null, RenderStatus.PLANNED,
        brief.json(), null, null);
    script.dms.add(m);
    return m;
  }

  Fidelity fidelity() {
    return slice.fidelity() == null ? Fidelity.SUMMARY : slice.fidelity();
  }

  static Map<String, Object> extra(Object... kv) {
    var m = new HashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
    return m;
  }
}
