package app.radiance.sim;

import app.radiance.content.ContentLibrary;
import app.radiance.content.Defs.EventDef;
import app.radiance.content.Vocab.Category;
import app.radiance.content.Vocab.Stance;
import app.radiance.kernel.RandomSource;
import app.radiance.sim.model.Item;
import app.radiance.sim.model.Person;
import app.radiance.sim.model.SimWorld;
import app.radiance.social.ReactionRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the community is doing while R is on (SPEC section 1): one small thing at a time, planned then and there,
 * so it's written just before it airs and can see what just happened. Who does what is decided here, in code
 * (P1), from who's around right now. Repetition kills this, so everything rests between uses and some turns pass.
 */
public final class Chatter {

  public enum Kind { POST, COMMENT, REPLY, ARGUMENT, LATE_REPLY, TYPO, TYPING, TAKE_BACK, PASS }

  /** One turn: what to air, and for a bit of typing that comes to nothing, who and where. */
  public record Beat(Kind kind, Script script, String typingActor, String typingPost, String takeBack) {
    static Beat of(Kind kind, Script script) {
      return new Beat(kind, script, null, null, null);
    }
  }

  /** A back-and-forth between two residents, which runs its course and dies. */
  static final class Argument {
    final String postId, a, b;
    String lastComment;
    String nextUp;
    int turnsLeft;

    Argument(String postId, String a, String b, String lastComment, int turnsLeft) {
      this.postId = postId;
      this.a = a;
      this.b = b;
      this.lastComment = lastComment;
      this.nextUp = b;
      this.turnsLeft = turnsLeft;
    }
  }

  /** A session's memory of what it's done, so nothing turns into a habit. */
  public static final class State {
    Argument argument;
    boolean opened;
    final Map<Kind, Long> lastOf = new HashMap<>();
    final Map<String, Long> lastBy = new HashMap<>();
    final Map<String, Integer> saidOn = new HashMap<>();
  }

  private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
  /** How long each kind of moment rests. */
  private static final Map<Kind, Long> REST = Map.of(Kind.ARGUMENT, 6 * MIN, Kind.LATE_REPLY, 20 * MIN, Kind.TYPO, 7 * MIN, Kind.TYPING, 5 * MIN, Kind.TAKE_BACK, 25 * MIN, Kind.POST, 3 * MIN);
  private static final long PERSON_RESTS = 3 * MIN;
  /** A thread burns out: past this many in a session it goes quiet, and the room talks about something else. */
  private static final int THREAD_BURNS_OUT = 7;
  private static final long RECENT_POSTS = 18 * HOUR;
  private static final int THREADS_TO_PICK_FROM = 8;
  private static final List<Category> EVERYDAY = List.of(Category.DAILY, Category.HOBBY, Category.FAMILY, Category.SOCIAL);
  /** The share of logins that open on an argument already going: some stay quiet. */
  private static final double OPENS_ON_AN_ARGUMENT = 0.7;

  private final ContentLibrary lib;
  private final Knobs k;
  private final Planner planner;
  private final SimWorld w;
  private final Plan plan;
  private final long now;
  private final long lands;
  private final RandomSource rng;
  private final State st;

  Chatter(ContentLibrary lib, Knobs k, SimWorld w, Plan plan, State st, long now, long lands, RandomSource rng) {
    this.lib = lib;
    this.k = k;
    this.planner = new Planner(lib, k);
    this.w = w;
    this.plan = plan;
    this.st = st;
    this.now = now;
    this.lands = lands;
    this.rng = rng;
  }

  /** The next thing to happen. {@code quiet} lets fewer things through, when hardly anyone's around. */
  Beat next() {
    var around = around();
    if (around.isEmpty()) return Beat.of(Kind.PASS, plan.script);
    if (!st.opened) {
      st.opened = true;
      if (rng.chance(OPENS_ON_AN_ARGUMENT)) {
        var opening = startArgument(around);
        if (opening != null) return opening;
      }
    }
    if (st.argument != null && rng.chance(0.75)) {
      var turn = continueArgument();
      if (turn != null) return turn;
    }
    double u = rng.nextDouble();
    Beat beat = null;
    if (u < 0.2) beat = rested(Kind.POST) ? post(around) : null;
    else if (u < 0.52) beat = comment(around, false);
    else if (u < 0.74) beat = reply(around);
    else if (u < 0.82) beat = rested(Kind.ARGUMENT) && st.argument == null ? startArgument(around) : null;
    else if (u < 0.87) beat = rested(Kind.TYPO) ? comment(around, true) : null;
    else if (u < 0.91) beat = rested(Kind.LATE_REPLY) ? lateReply(around) : null;
    else if (u < 0.94) beat = rested(Kind.TYPING) ? typing(around) : null;
    else if (u < 0.96) beat = rested(Kind.TAKE_BACK) ? takeBack() : null;
    if (beat == null && u < 0.96) beat = comment(around, false);
    return beat == null ? Beat.of(Kind.PASS, plan.script) : beat;
  }

  /** Someone around reacts to something recent: no writing, and it's what makes a feed tick. */
  Script reaction() {
    var around = around();
    var threads = threads();
    if (around.isEmpty() || threads.isEmpty()) return plan.script;
    var b = around.get(rng.nextInt(around.size()));
    var fresh = threads.stream().filter(i -> !i.authorId.equals(b.id) && !i.reactors.contains(b.id) && !avoids(b, i.authorId)).toList();
    if (fresh.isEmpty()) return plan.script;
    var post = fresh.get(rng.nextInt(fresh.size()));
    var e = w.edgeOrNull(b.id, post.authorId);
    plan.reaction(lands, ReactionRow.Target.POST, post.id, b.id, Stances.reaction(null, b, post, e == null ? 0 : e.affinity, rng));
    return plan.script;
  }

  // ------------------------------------------------------------------------------------------ who's here

  /** Residents around right now who R can see or hear about, his friends first. */
  private List<Person> around() {
    var user = w.userId;
    var here = new ArrayList<Person>();
    for (var p : w.residents()) {
      if (!Presence.around(p, now, w.zone, w.seed)) continue;
      if (st.lastBy.getOrDefault(p.id, Long.MIN_VALUE) > now - PERSON_RESTS) continue;
      var e = user == null ? null : w.edgeOrNull(p.id, user);
      if (e != null && e.connected) here.add(p);
    }
    if (!here.isEmpty()) return here;
    // nobody R knows is on: a friend who's about to be
    for (var p : w.residents()) {
      var e = user == null ? null : w.edgeOrNull(p.id, user);
      if (e != null && e.connected && st.lastBy.getOrDefault(p.id, Long.MIN_VALUE) <= now - PERSON_RESTS && rng.chance(0.15)) here.add(p);
    }
    return here;
  }

  private boolean rested(Kind kind) {
    return st.lastOf.getOrDefault(kind, Long.MIN_VALUE) <= now - REST.getOrDefault(kind, 0L);
  }

  private void used(Kind kind, String person) {
    st.lastOf.put(kind, now);
    st.lastBy.put(person, now);
  }

  private boolean avoids(Person b, String other) {
    if (b.id.equals(other)) return true;
    var e = w.edgeOrNull(b.id, other);
    if (e != null && e.kind == app.radiance.graph.RelationshipRow.Kind.EX) return true;
    return w.activeTags(b.id, now, lib).contains("avoids:" + other);
  }

  /** Threads R could be looking at, newest first. */
  private List<Item> threads() {
    return w.items.values().stream()
        .filter(i -> i.type == Item.Type.POST && i.aired() && i.time > now - RECENT_POSTS && canSee(i))
        .filter(i -> st.saidOn.getOrDefault(i.id, 0) < THREAD_BURNS_OUT || i.authorId.equals(w.userId))
        .sorted(Comparator.comparingLong((Item i) -> -i.time).thenComparing(i -> i.id))
        .limit(THREADS_TO_PICK_FROM).toList();
  }

  private boolean canSee(Item post) {
    if (post.authorId.equals(w.userId) || post.publicAudience || post.headlineId != null) return true;
    var e = w.edgeOrNull(w.userId, post.authorId);
    return e != null && e.connected;
  }

  private boolean knows(Person b, String author) {
    var e = w.edgeOrNull(b.id, author);
    return e != null && e.connected;
  }

  // --------------------------------------------------------------------------------------------- beats

  private Beat post(List<Person> around) {
    var a = around.get(rng.nextInt(around.size()));
    LocalDate date = w.date(now);
    var triggers = app.radiance.world.Calendar.triggers(date);
    var defs = new ArrayList<>(new TreeMap<>(lib.events()).values());
    for (int i = defs.size() - 1; i > 0; i--) java.util.Collections.swap(defs, i, rng.nextInt(i + 1));
    for (EventDef def : defs) {
      if (!EVERYDAY.contains(def.category()) || !Planner.posts(def)) continue;
      if (def.trigger() != null && def.trigger().headline() != null) continue;
      if (!Planner.calendarAllows(def, a, date, triggers, w)) continue;
      if (!Eligibility.holds(def, a, w, lib, null, now)) continue;
      if (Planner.onCooldown(w, a, def, now) || Planner.someoneJustDid(w, def, now)) continue;
      var participants = planner.participants(plan, def, a, date, rng);
      if (participants == null) continue;
      planner.fire(plan, def, a, participants, lands);
      used(Kind.POST, a.id);
      return Beat.of(Kind.POST, plan.script);
    }
    return null;
  }

  private Beat comment(List<Person> around, boolean withATypo) {
    var threads = threads();
    if (threads.isEmpty()) return null;
    for (int tries = 0; tries < 6; tries++) {
      var post = threads.get(Math.min(rng.nextInt(threads.size()), rng.nextInt(threads.size())));
      if (Planner.crowded(w, post, 0)) continue;
      var b = pick(around, x -> !avoids(x, post.authorId) && !post.commenters.contains(x.id) && (knows(x, post.authorId) || post.headlineId != null));
      if (b == null) continue;
      var author = w.person(post.authorId);
      var stance = stanceOn(b, author, post);
      if (withATypo) {
        var c = plan.comment("chatter:" + b.id + ":" + post.id + ":" + now, lands, post.id, null, b.id,
            brief(post, stance, post.authorId, null, "typed in a hurry: one obvious typo in it, left uncorrected"));
        plan.comment("chatter-fix:" + c.id(), lands + 6_000 + rng.nextInt(7_000), post.id, null, b.id,
            brief(post, stance, post.authorId, null, "a correction of that typo right after, and nothing else: a star and the fixed word, like *their"));
        used(Kind.TYPO, b.id);
        return Beat.of(Kind.TYPO, plan.script);
      }
      plan.comment("chatter:" + b.id + ":" + post.id + ":" + now, lands, post.id, null, b.id, Planner.commentBrief(post, stance, post.authorId, null));
      used(Kind.COMMENT, b.id);
      st.saidOn.merge(post.id, 1, Integer::sum);
      return Beat.of(Kind.COMMENT, plan.script);
    }
    return null;
  }

  private Beat reply(List<Person> around) {
    var threads = threads();
    var ids = threads.stream().map(i -> i.id).toList();
    var said = w.items.values().stream()
        .filter(i -> i.type == Item.Type.COMMENT && i.aired() && ids.contains(i.postId) && !i.authorId.equals(w.userId) && i.time > now - 3 * HOUR)
        .sorted(Comparator.comparingLong((Item i) -> -i.time).thenComparing(i -> i.id)).limit(10).toList();
    if (said.isEmpty()) return null;
    var c = said.get(Math.min(rng.nextInt(said.size()), rng.nextInt(said.size())));
    var post = w.items.get(c.postId);
    if (post == null) return null;
    var b = pick(around, x -> !avoids(x, c.authorId) && !avoids(x, post.authorId) && (knows(x, c.authorId) || knows(x, post.authorId)));
    if (b == null) return null;
    var stance = stanceOn(b, w.person(c.authorId), c);
    plan.comment("chatter-reply:" + b.id + ":" + c.id, lands, post.id, c.id, b.id, Planner.commentBrief(post, stance, c.authorId, null));
    used(Kind.REPLY, b.id);
    st.saidOn.merge(post.id, 1, Integer::sum);
    return Beat.of(Kind.REPLY, plan.script);
  }

  /** Two people who'd go at it, on a thread one of them is already in. */
  private Beat startArgument(List<Person> around) {
    Item post = null;
    Item opener = null;
    var threads = new ArrayList<>(threads());
    threads.sort(Comparator.comparingDouble((Item i) -> -i.controversy).thenComparing(i -> -i.time).thenComparing(i -> i.id));
    for (var candidate : threads) {
      var said = w.items.values().stream().filter(i -> i.type == Item.Type.COMMENT && i.aired() && candidate.id.equals(i.postId) && !i.authorId.equals(w.userId))
          .max(Comparator.comparingLong((Item i) -> i.time).thenComparing(i -> i.id)).orElse(null);
      if (said != null) {
        post = candidate;
        opener = said;
        break;
      }
    }
    if (post == null) return null;
    var other = opener;
    var author = post.authorId;
    var b = around.stream().filter(x -> !avoids(x, other.authorId) && !avoids(x, author))
        .max(Comparator.comparingDouble((Person x) -> x.trait("argumentativeness") + 20 * rng.nextDouble()).thenComparing(x -> x.id)).orElse(null);
    if (b == null) return null;
    st.argument = new Argument(post.id, opener.authorId, b.id, opener.id, 3 + rng.nextInt(4));
    return argumentTurn(st.argument);
  }

  private Beat continueArgument() {
    var a = st.argument;
    if (a.turnsLeft <= 0) {
      st.argument = null;
      return null;
    }
    return argumentTurn(a);
  }

  private Beat argumentTurn(Argument a) {
    var post = w.items.get(a.postId);
    var parent = w.items.get(a.lastComment);
    if (post == null || parent == null || !parent.aired()) return null;
    var who = w.person(a.nextUp);
    String other = a.nextUp.equals(a.a) ? a.b : a.a;
    double u = rng.nextDouble();
    var stance = u < 0.15 ? Stance.JOKE : who.trait("agreeableness") < 40 && u < 0.45 ? Stance.MOCK : Stance.DISAGREE;
    var c = plan.comment("chatter-arg:" + who.id + ":" + a.lastComment, lands, post.id, a.lastComment, who.id, Planner.commentBrief(post, stance, other, "debate_exchange"));
    a.lastComment = c.id();
    a.nextUp = other;
    a.turnsLeft--;
    st.saidOn.merge(post.id, 1, Integer::sum);
    used(Kind.ARGUMENT, who.id);
    return Beat.of(Kind.ARGUMENT, plan.script);
  }

  /** A reply to something from a couple of days ago, by someone just getting to it. */
  private Beat lateReply(List<Person> around) {
    var old = w.items.values().stream()
        .filter(i -> i.type == Item.Type.COMMENT && i.aired() && i.time < now - DAY && i.time > now - 3 * DAY)
        .sorted(Comparator.comparing((Item i) -> i.id)).toList();
    if (old.isEmpty()) return null;
    var c = old.get(rng.nextInt(old.size()));
    var post = w.items.get(c.postId);
    if (post == null || !canSee(post)) return null;
    var b = pick(around, x -> !avoids(x, c.authorId) && !x.id.equals(post.authorId) && knows(x, post.authorId));
    if (b == null) return null;
    var stance = stanceOn(b, w.person(c.authorId), c);
    plan.comment("chatter-late:" + b.id + ":" + c.id, lands, post.id, c.id, b.id,
        brief(post, stance, c.authorId, null, "replying days late, having only just seen this; a sentence at most about that"));
    used(Kind.LATE_REPLY, b.id);
    return Beat.of(Kind.LATE_REPLY, plan.script);
  }

  /** Someone starts writing a comment and thinks better of it. */
  private Beat typing(List<Person> around) {
    var threads = threads();
    if (threads.isEmpty()) return null;
    var post = threads.get(rng.nextInt(threads.size()));
    var b = pick(around, x -> !avoids(x, post.authorId));
    if (b == null) return null;
    used(Kind.TYPING, b.id);
    return new Beat(Kind.TYPING, plan.script, b.id, post.id, null);
  }

  /** Someone thinks better of what they just said. Never once R or anyone has answered it. */
  private Beat takeBack() {
    var ids = threads().stream().map(i -> i.id).toList();
    var replied = new java.util.HashSet<String>();
    w.items.values().forEach(i -> {
      if (i.type == Item.Type.COMMENT && i.parentId != null) replied.add(i.parentId);
    });
    var c = w.items.values().stream()
        .filter(i -> i.type == Item.Type.COMMENT && i.aired() && ids.contains(i.postId) && !i.authorId.equals(w.userId)
            && i.time > now - 6 * MIN && !replied.contains(i.id) && i.reactionCounts.isEmpty())
        .max(Comparator.comparingLong((Item i) -> i.time).thenComparing(i -> i.id)).orElse(null);
    if (c == null) return null;
    used(Kind.TAKE_BACK, c.authorId);
    return new Beat(Kind.TAKE_BACK, plan.script, null, null, c.id);
  }

  // ----------------------------------------------------------------------------------------------- helps

  private Person pick(List<Person> people, java.util.function.Predicate<Person> ok) {
    var fit = people.stream().filter(ok).toList();
    return fit.isEmpty() ? null : fit.get(rng.nextInt(fit.size()));
  }

  private Stance stanceOn(Person b, Person author, Item item) {
    var stance = Stances.comment(w, b, author, item, lib, k, w.day(now), rng);
    return stance == Stance.REACT_ONLY || stance == Stance.META ? Stance.SUPPORT : stance;
  }

  private static Brief brief(Item on, Stance stance, String toward, String interaction, String beat) {
    String key = interaction != null ? interaction : Airing.interactionFor(stance.name());
    return new Brief(on.defId, on.category, beat, stance.name(), toward, key, on.topics, on.controversy, on.drama, Plan.extra("on", on.id));
  }
}
