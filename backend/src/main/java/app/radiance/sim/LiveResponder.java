package app.radiance.sim;

import app.radiance.content.ContentLibrary;
import app.radiance.content.Vocab.Stance;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.RandomSource;
import app.radiance.sim.model.Item;
import app.radiance.sim.model.Person;
import app.radiance.sim.model.SimWorld;
import app.radiance.social.ReactionRow;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who answers R, and how (SPEC §5.1 live play, §5.8, §5.9), without the Pacer: impressions first —
 * every resident who sees his post or comment updates how they see him — then the engagement model
 * picks who reacts, comments or replies, with R's salience and the attention caps. Responses are
 * written as scripted items (origin LIVE) a few seconds to minutes out, and air like everything else.
 * Delays are lognormal, past {@code horizon}: in live play the Pacer moves each response it releases up
 * to its release time ({@code UserActions}), and whatever it doesn't release airs later as background.
 */
public final class LiveResponder {

  /** R laughing this hard at a tragedy reads as callous. */
  private static final double CALLOUS_HUMOR = 0.5;
  /** People online right now are likelier to answer than the script's later sessions: this is what makes a post feel heard. */
  private static final double LIVE_PULL = 0.4;
  /** Generous attention (SPEC P3): while R has friends, a post is never met with silence. */
  private static final int MIN_REACTIONS = 3;
  private static final int MIN_COMMENTS = 2;
  /** The floor's comments come from among this many of R's closest, so it isn't the same friend answering every time. */
  private static final int FLOOR_COMMENTERS = 5;
  /** Below this affinity, someone R talks to may not even leave a reaction. */
  private static final double COLD_SHOULDER = 30;

  private final ContentLibrary lib;
  private final Knobs k;

  public LiveResponder(ContentLibrary lib, Knobs k) {
    this.lib = lib;
    this.k = k;
  }

  /** Residents' responses to R's post {@code post}, which has just aired at {@code t}. */
  public Script onPost(SimWorld w, Item post, Map<String, Double> features, int postsToday, long t) {
    return onPost(w, post, features, postsToday, t, 0, Set.of());
  }

  /** Tops a quiet post up to the floors, from whoever likes R most and hasn't answered yet. */
  private void floors(SimWorld w, Plan plan, Item post, app.radiance.sim.model.Person user, String day, long t, long horizon, Set<String> reacted, int commenters) {
    var answered = new java.util.HashSet<>(reacted);
    plan.script.reactions.forEach(x -> answered.add(x.actorId()));
    var wrote = new java.util.HashSet<String>();
    plan.script.comments.forEach(x -> wrote.add(x.authorId()));
    var friends = w.residents().stream().filter(b -> {
      var e = w.edgeOrNull(b.id, user.id);
      return e != null && e.connected;
    }).sorted(java.util.Comparator.comparingDouble((app.radiance.sim.model.Person b) -> -w.edge(b.id, user.id).affinity).thenComparing(b -> b.id)).toList();
    int reactions = answered.size();
    for (var b : friends) {
      if (reactions >= MIN_REACTIONS) break;
      if (answered.contains(b.id)) continue;
      var rng = RandomSource.forActor(w.seed, b.id, t).derive("floor-react:" + post.id);
      plan.reaction(t + horizon + delay(rng, 90_000, 20 * 60_000L), ReactionRow.Target.POST, post.id, b.id,
          Stances.reaction(null, b, post, w.edge(b.id, user.id).affinity, rng));
      reactions++;
    }
    var close = new java.util.ArrayList<>(friends.subList(0, Math.min(FLOOR_COMMENTERS, friends.size())));
    var shuffle = RandomSource.forSlice(w.seed, t).derive("floor-order:" + post.id);
    for (int i = close.size() - 1; i > 0; i--) java.util.Collections.swap(close, i, shuffle.nextInt(i + 1));
    for (var b : close) {
      if (commenters >= MIN_COMMENTS) break;
      if (wrote.contains(b.id)) continue;
      var rng = RandomSource.forActor(w.seed, b.id, t).derive("floor-comment:" + post.id);
      var stance = Stances.comment(w, b, user, post, lib, k, day, rng);
      if (stance == Stance.REACT_ONLY) stance = Stance.SUPPORT;
      plan.comment("live:" + b.id + ":" + post.id, t + horizon + delay(rng, 8 * 60_000L, 30 * 60_000L), post.id, null, b.id,
          Planner.commentBrief(post, stance, user.id, null));
      commenters++;
    }
  }

  /**
   * As above, with every delay pushed past {@code horizon}, and no second reaction from residents in
   * {@code reacted} (the reflex phase already queued theirs).
   */
  public Script onPost(SimWorld w, Item post, Map<String, Double> features, int postsToday, long t, long horizon,
      Set<String> reacted) {
    var user = w.person(w.userId);
    var plan = new Plan(w, lib, k, new Slice(t, t + 3_600_000L, Fidelity.FULL),
        new Plan.Inputs(List.of(), null, Set.of(), Map.of(), ContentOrigin.LIVE), new java.util.HashMap<>());
    var planner = new Planner(lib, k);
    var r = lib.engagement();
    String day = w.day(t);
    double saturation = 1.0 / (1 + 0.3 * Math.max(0, postsToday - 1));
    int commenters = 0;
    var order = new java.util.ArrayList<>(w.residents());
    // who gets there first is a matter of luck, not id order
    var shuffle = RandomSource.forSlice(w.seed, t).derive("live-order:" + post.id);
    for (int i = order.size() - 1; i > 0; i--) java.util.Collections.swap(order, i, shuffle.nextInt(i + 1));
    for (var b : order) {
      var e = w.edgeOrNull(b.id, user.id);
      if (e == null || !e.connected) continue;
      var rng = RandomSource.forActor(w.seed, b.id, t).derive("live:" + post.id);
      if (!rng.chance(0.8)) continue; // didn't see it in time
      Impressions.see(w, b, user, features, saturation, lib, k, t);
      if (planner.attentionUsed(plan, b, day) >= b.routine.attentionBudget()) continue;
      // saturation grows with each comment planned here, as it would as they land
      double u = planner.utility(w, b, post, user, day, rng) + LIVE_PULL - r.wSat() * Math.min(1, commenters / 8.0);
      boolean comment = !Planner.crowded(w, post, commenters)
          && rng.chance(Planner.sigmoid(r.steepness() * (u - r.thetaComment())));
      boolean react = !reacted.contains(b.id)
          && (comment ? rng.chance(0.7) : rng.chance(Planner.sigmoid(r.steepness() * (u - r.thetaReact()))));
      double aff = w.edge(b.id, user.id).affinity;
      Stance stance = comment ? Stances.comment(w, b, user, post, lib, k, day, rng) : null;
      if (react) {
        long at = t + horizon + delay(rng, 90_000, 2 * 3_600_000L);
        plan.reaction(at, ReactionRow.Target.POST, post.id, b.id, Stances.reaction(stance, b, post, aff, rng));
        planner.spend(b, day);
      }
      if (comment) {
        long at = t + horizon + delay(rng, 8 * 60_000L, 3 * 3_600_000L);
        plan.comment("live:" + b.id + ":" + post.id, at, post.id, null, b.id, Planner.commentBrief(post, stance, user.id, null));
        planner.spend(b, day);
        commenters++;
      }
    }
    floors(w, plan, post, user, day, t, horizon, reacted, commenters);
    return plan.script;
  }

  /**
   * Responses to R's comment {@code comment} on someone's post: the person he addressed may answer
   * (answering R is always allowed), and a few others may react.
   */
  public Script onComment(SimWorld w, Item comment, Map<String, Double> features, long t) {
    return onComment(w, comment, features, t, 0);
  }

  public Script onComment(SimWorld w, Item comment, Map<String, Double> features, long t, long horizon) {
    var user = w.person(w.userId);
    var plan = new Plan(w, lib, k, new Slice(t, t + 3_600_000L, Fidelity.FULL),
        new Plan.Inputs(List.of(), null, Set.of(), Map.of(), ContentOrigin.LIVE), new java.util.HashMap<>());
    var post = w.items.get(comment.postId);
    if (post == null) return plan.script;
    String day = w.day(t);
    var seen = new java.util.LinkedHashSet<String>();
    seen.add(comment.toward);
    seen.add(post.authorId);
    seen.addAll(post.commenters);
    seen.remove(user.id);
    for (String id : seen) {
      var b = w.people.get(id);
      if (b == null || b.user) continue;
      var rng = RandomSource.forActor(w.seed, b.id, t).derive("live-comment:" + comment.id);
      Impressions.see(w, b, user, features, 1.0, lib, k, t);
      double aff = w.edge(b.id, user.id).affinity;
      if (id.equals(comment.toward)) {
        // R answering a comment on his own post can close the exchange; on theirs, it opens one. Generous
        // attention (SPEC P3): being talked to almost always gets an answer, unless they can't stand him
        boolean onHisPost = post.authorId.equals(user.id);
        double p = (onHisPost ? 0.3 : 0.9) + 0.3 * Math.max(0, aff) / 100 + Math.min(0, aff) / 100
            + (Airing.CLASH.contains(comment.stance) ? 0.25 : 0)
            + ("QUESTION".equals(comment.stance) || "FLIRT".equals(comment.stance) ? 0.3 : 0);
        if (rng.chance(Math.min(0.97, p))) {
          var edge = w.edge(b.id, user.id);
          var stance = Airing.CLASH.contains(comment.stance) ? (rng.chance(0.3) ? Stance.JOKE : Stance.DISAGREE)
              : "FLIRT".equals(comment.stance) ? Stances.answerToFlirt(b, user, edge.attraction, edge.chemistry, k, rng)
              : Stances.comment(w, b, user, comment, lib, k, day, rng);
          plan.comment("live-reply:" + b.id + ":" + comment.id, t + horizon + delay(rng, 90_000, 20 * 60_000L), post.id,
              comment.id, b.id, Planner.commentBrief(post, stance, user.id,
                  Airing.CLASH.contains(comment.stance) && Airing.CLASH.contains(stance.name()) ? "debate_exchange" : null));
        } else if (aff > -COLD_SHOULDER || rng.chance(0.45)) {
          plan.reaction(t + horizon + delay(rng, 45_000, 15 * 60_000L), ReactionRow.Target.COMMENT, comment.id, b.id,
              Airing.UGLY.contains(comment.stance) ? Stances.stung(b, rng)
                  : Stances.reaction(Stance.valueOf(comment.stance == null ? "SUPPORT" : comment.stance), b, post, aff, rng));
        }
      } else if (rng.chance(0.2 + Math.max(0, aff) / 400)) {
        plan.reaction(t + horizon + delay(rng, 3 * 60_000L, 3_600_000L), ReactionRow.Target.COMMENT, comment.id, b.id,
            Stances.reaction(Stance.valueOf(comment.stance == null ? "SUPPORT" : comment.stance), b, post, aff, rng));
      }
    }
    if (post.tragic && features.getOrDefault("humor", 0.0) >= CALLOUS_HUMOR) {
      Planner.backlash(plan, post, comment.id, user.id, t + horizon, RandomSource.forActor(w.seed, user.id, t).derive("backlash:" + comment.id));
    }
    return plan.script;
  }

  /** Lognormal around {@code median}, capped. */
  static long delay(RandomSource rng, long median, long cap) {
    return Math.min(cap, Math.max(2_000, (long) (median * Math.exp(rng.nextGaussian(0, 0.8)))));
  }
}
