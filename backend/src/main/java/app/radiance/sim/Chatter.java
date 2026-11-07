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

  public enum Kind { POST, COMMENT, REPLY, ARGUMENT, LATE_REPLY, TYPO, TYPING, TAKE_BACK, TAG, OFFLINE, WRONG_CHAT, AWKWARD, PULLED_IN, ASKED_FIRST, TALKED_ABOUT, GOSSIP, QUOTED, NEIGHBORHOOD, SAVED_SEAT, DM_PHOTO, PICKED_FIGHT, CALLED_OUT, PASS }

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

  record Local(String postId, List<String> people, long until, boolean sameBlock) {}

  /** A session's memory of what it's done, so nothing turns into a habit. */
  public static final class State {
    Argument argument;
    boolean opened;
    Map<Kind, Long> lastOf = new HashMap<>();
    final Map<String, Long> lastBy = new HashMap<>();
    final Map<String, Integer> saidOn = new HashMap<>();
    /** Something happening on one block right now, good for an hour. */
    Local local;
    /** Drop-in mode (ADR 0024): short visits, so more happens, sooner, and fights are the main event. */
    boolean dropIn;
    /** How long moments rest, as a share of {@link #REST}: short visits can't wait hours for one. */
    double restScale = 1;
    /** What R has said in public in the last few hours, for residents who take issue with it in a DM. */
    List<String> heSaidLately = List.of();
    final java.util.Set<String> calledOutLines = new java.util.HashSet<>();
    final java.util.Set<String> calledOutBy = new java.util.HashSet<>();

    public State() {}

    /** A session that remembers when each moment last happened: logging in again doesn't make it new. */
    public State(Map<Kind, Long> remembered) {
      this.lastOf = remembered;
    }

    /** Drop-in mode (ADR 0024): moments rest {@code restScale} as long, and the session leans on fights and news. */
    public State dropIn(double restScale) {
      this.dropIn = true;
      this.restScale = restScale;
      return this;
    }
  }

  private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
  /** How long each kind of moment rests. */
  private static final Map<Kind, Long> REST = Map.ofEntries(Map.entry(Kind.ARGUMENT, 6 * MIN), Map.entry(Kind.LATE_REPLY, 20 * MIN), Map.entry(Kind.TYPO, 7 * MIN),
      Map.entry(Kind.TYPING, 5 * MIN), Map.entry(Kind.TAKE_BACK, 25 * MIN), Map.entry(Kind.POST, 3 * MIN), Map.entry(Kind.TAG, 10 * MIN), Map.entry(Kind.OFFLINE, 15 * MIN),
      Map.entry(Kind.WRONG_CHAT, 25 * MIN), Map.entry(Kind.AWKWARD, 20 * MIN), Map.entry(Kind.PULLED_IN, 90 * MIN), Map.entry(Kind.ASKED_FIRST, 3 * HOUR),
      Map.entry(Kind.TALKED_ABOUT, 2 * HOUR), Map.entry(Kind.GOSSIP, 6 * HOUR), Map.entry(Kind.QUOTED, 4 * HOUR), Map.entry(Kind.NEIGHBORHOOD, 4 * HOUR), Map.entry(Kind.SAVED_SEAT, 8 * HOUR), Map.entry(Kind.DM_PHOTO, 45 * MIN),
      Map.entry(Kind.PICKED_FIGHT, 8 * MIN), Map.entry(Kind.CALLED_OUT, 12 * MIN));
  /** Drop-in mode: the share of turns for something out of the ordinary, the share that open on a fight, and a room never emptier than this. */
  private static final double DROP_IN_SPECIAL = 0.35, DROP_IN_CALL_OUT = 0.45, DROP_IN_NEWS_FIRST = 0.55;
  private static final int DROP_IN_ROOM = 6;
  /** The share of turns that try for something out of the ordinary, when one has rested long enough. */
  private static final double SOMETHING_SPECIAL = 0.22;
  private static final java.util.Set<String> FEED_TEXTURE = java.util.Set.of("borrow_something", "who_knows_a_guy", "meme_post", "throwback", "locked_out");
  private static final long PERSON_RESTS = 3 * MIN;
  private static final double GOSSIP_FAN = 55, GOSSIP_COOL = 5;
  /** R's direction: one post in three while he's online has a picture. */
  static final double PHOTO_SHARE = 1.0 / 3;
  /** A picture takes longer than words: time to write it, draw it, and still land on time. */
  private static final long PHOTO_LEAD = 25_000;
  private static final double CLOSE_ENOUGH_FOR_PHOTOS = 45;
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
  private final List<String> hisLines;

  /** @param hisLines what R has said in public lately: only his posts and comments, never his DMs */
  Chatter(ContentLibrary lib, Knobs k, SimWorld w, Plan plan, State st, long now, long lands, RandomSource rng, List<String> hisLines) {
    this.lib = lib;
    this.k = k;
    this.planner = new Planner(lib, k);
    this.w = w;
    this.plan = plan;
    this.st = st;
    this.now = now;
    this.lands = lands;
    this.rng = rng;
    this.hisLines = hisLines;
  }

  /** The next thing to happen. {@code quiet} lets fewer things through, when hardly anyone's around. */
  Beat next() {
    var around = around();
    if (around.isEmpty()) return Beat.of(Kind.PASS, plan.script);
    if (!st.opened) {
      st.opened = true;
      if (st.dropIn || rng.chance(OPENS_ON_AN_ARGUMENT)) {
        var opening = startArgument(around);
        if (opening == null && st.dropIn) opening = pickFight(around);
        if (opening != null) return opening;
      }
    }
    if (st.argument != null && rng.chance(st.dropIn ? 0.85 : 0.75)) {
      var turn = continueArgument();
      if (turn != null) return turn;
    }
    if (st.dropIn && !st.heSaidLately.isEmpty() && rested(Kind.CALLED_OUT) && rng.chance(DROP_IN_CALL_OUT)) {
      var called = calledOut(around);
      if (called != null) return called;
    }
    if (st.dropIn && st.argument == null && rested(Kind.PICKED_FIGHT) && rng.chance(0.5)) {
      var fight = pickFight(around);
      if (fight != null) return fight;
    }
    if (st.local != null && now < st.local.until() && rng.chance(0.35)) {
      var more = aroundTheBlock();
      if (more != null) return more;
    }
    if (rng.chance(st.dropIn ? DROP_IN_SPECIAL : SOMETHING_SPECIAL)) {
      var special = special(around);
      if (special != null) return special;
    }
    double u = rng.nextDouble();
    Beat beat = null;
    if (u < 0.2) beat = rested(Kind.POST) ? post(around) : null;
    else if (u < 0.47) beat = comment(around, false);
    else if (u < 0.52) beat = rested(Kind.TAG) ? tag(around) : null;
    else if (u < 0.70) beat = reply(around);
    else if (u < 0.74) beat = rested(Kind.OFFLINE) ? takeItOffline(around) : null;
    else if (u < 0.82) beat = rested(Kind.ARGUMENT) && st.argument == null ? startArgument(around) : null;
    else if (u < 0.87) beat = rested(Kind.TYPO) ? comment(around, true) : null;
    else if (u < 0.91) beat = rested(Kind.LATE_REPLY) ? lateReply(around) : null;
    else if (u < 0.94) beat = rested(Kind.TYPING) ? typing(around) : null;
    else if (u < 0.96) beat = rested(Kind.TAKE_BACK) ? takeBack() : null;
    if (beat == null && u < 0.96) beat = comment(around, false);
    return beat == null ? Beat.of(Kind.PASS, plan.script) : beat;
  }

  /** Something out of the ordinary, whichever has rested long enough and has what it needs right now. */
  private Beat special(List<Person> around) {
    var kinds = new ArrayList<>(List.of(Kind.WRONG_CHAT, Kind.AWKWARD, Kind.PULLED_IN, Kind.ASKED_FIRST, Kind.TALKED_ABOUT, Kind.GOSSIP, Kind.QUOTED, Kind.NEIGHBORHOOD, Kind.SAVED_SEAT, Kind.DM_PHOTO));
    for (int i = kinds.size() - 1; i > 0; i--) java.util.Collections.swap(kinds, i, rng.nextInt(i + 1));
    for (var kind : kinds) {
      if (!rested(kind)) continue;
      var beat = switch (kind) {
        case WRONG_CHAT -> wrongChat(around);
        case AWKWARD -> awkward(around);
        case PULLED_IN -> pulledIn();
        case ASKED_FIRST -> askedFirst(around);
        case TALKED_ABOUT -> talkedAbout(around);
        case GOSSIP -> gossip(around);
        case QUOTED -> quoted(around);
        case NEIGHBORHOOD -> neighborhood();
        case SAVED_SEAT -> savedSeat(around);
        case DM_PHOTO -> dmPhoto(around);
        default -> null;
      };
      if (beat != null) return beat;
    }
    return null;
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
    if (st.dropIn && here.size() < DROP_IN_ROOM) {
      // a short visit never finds the room empty: friends who'd usually be on later are on now
      var more = new ArrayList<Person>();
      for (var p : w.residents()) {
        var e = user == null ? null : w.edgeOrNull(p.id, user);
        if (e != null && e.connected && !here.contains(p) && st.lastBy.getOrDefault(p.id, Long.MIN_VALUE) <= now - PERSON_RESTS) more.add(p);
      }
      for (int i = more.size() - 1; i > 0; i--) java.util.Collections.swap(more, i, rng.nextInt(i + 1));
      here.addAll(more.subList(0, Math.min(more.size(), DROP_IN_ROOM - here.size())));
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
    return st.lastOf.getOrDefault(kind, Long.MIN_VALUE) <= now - (long) (REST.getOrDefault(kind, 0L) * st.restScale);
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

  Beat post(List<Person> around) {
    var a = around.get(rng.nextInt(around.size()));
    LocalDate date = w.date(now);
    var triggers = app.radiance.world.Calendar.triggers(date);
    var defs = new ArrayList<>(new TreeMap<>(lib.events()).values());
    for (int i = defs.size() - 1; i > 0; i--) java.util.Collections.swap(defs, i, rng.nextInt(i + 1));
    // life admin and the feed's odds and ends come up more than a shuffle would give them
    if (rng.chance(0.35)) defs.sort(Comparator.comparing((EventDef d) -> !FEED_TEXTURE.contains(d.id())));
    boolean photo = rng.chance(PHOTO_SHARE);
    for (EventDef def : defs) {
      if (!EVERYDAY.contains(def.category()) || !Planner.posts(def)) continue;
      if ((def.output().post() == app.radiance.content.Vocab.PostOutput.PHOTO) != photo) continue;
      if (def.trigger() != null && def.trigger().headline() != null) continue;
      if (!Planner.calendarAllows(def, a, date, triggers, w)) continue;
      if (!Eligibility.holds(def, a, w, lib, null, now)) continue;
      if (Planner.onCooldown(w, a, def, now) || Planner.someoneJustDid(w, def, now)) continue;
      var participants = planner.participants(plan, def, a, date, rng);
      if (participants == null) continue;
      planner.fire(plan, def, a, participants, photo ? lands + PHOTO_LEAD : lands);
      used(Kind.POST, a.id);
      return Beat.of(Kind.POST, plan.script);
    }
    return null;
  }

  private Beat comment(List<Person> around, boolean withATypo) {
    var threads = threads();
    if (threads.isEmpty()) return null;
    // on someone's birthday, everyone piles on
    var birthdays = threads.stream().filter(i -> i.defId != null && i.defId.startsWith("birthday")).toList();
    if (!withATypo && !birthdays.isEmpty() && rng.chance(0.7)) {
      var post = birthdays.get(rng.nextInt(birthdays.size()));
      var b = pick(around, x -> !avoids(x, post.authorId) && !post.commenters.contains(x.id) && knows(x, post.authorId));
      if (b != null) {
        plan.comment("chatter-bday:" + b.id + ":" + post.id, lands, post.id, null, b.id, Planner.commentBrief(post, Stance.CONGRATS, post.authorId, null));
        used(Kind.COMMENT, b.id);
        return Beat.of(Kind.COMMENT, plan.script);
      }
    }
    var news = st.dropIn ? threads.stream().filter(i -> i.headlineId != null).toList() : List.<Item>of();
    for (int tries = 0; tries < 6; tries++) {
      var post = !news.isEmpty() && rng.chance(DROP_IN_NEWS_FIRST) ? news.get(rng.nextInt(news.size()))
          : threads.get(Math.min(rng.nextInt(threads.size()), rng.nextInt(threads.size())));
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
        st.saidOn.merge(post.id, 2, Integer::sum);
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
    st.argument = new Argument(post.id, opener.authorId, b.id, opener.id, st.dropIn ? 5 + rng.nextInt(5) : 3 + rng.nextInt(4));
    return argumentTurn(st.argument);
  }

  private Beat continueArgument() {
    var a = st.argument;
    if (a.turnsLeft <= 0 || st.saidOn.getOrDefault(a.postId, 0) >= THREAD_BURNS_OUT) {
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

  /** Someone pulls a friend into a thread: a glimpse of what's between them. */
  private Beat tag(List<Person> around) {
    var threads = threads();
    if (threads.isEmpty()) return null;
    var post = threads.get(rng.nextInt(threads.size()));
    var b = pick(around, x -> !avoids(x, post.authorId) && !post.commenters.contains(x.id) && knows(x, post.authorId));
    if (b == null) return null;
    var pals = w.residents().stream().filter(x -> !x.id.equals(b.id) && !x.id.equals(post.authorId) && knows(b, x.id) && !avoids(b, x.id)).toList();
    if (pals.isEmpty()) return null;
    var pal = pals.get(rng.nextInt(pals.size()));
    var extra = new java.util.LinkedHashMap<String, Object>(Plan.extra("on", post.id));
    extra.put("mentions", List.of(pal.id));
    plan.comment("chatter-tag:" + b.id + ":" + post.id + ":" + now, lands, post.id, null, b.id,
        new Brief(post.defId, post.category, "tags @" + pal.handle + " with barely any words, as if this is obviously about something only the two of them know", Stance.JOKE.name(),
            post.authorId, "joke_comment", post.topics, post.controversy, post.drama, extra));
    used(Kind.TAG, b.id);
    st.saidOn.merge(post.id, 1, Integer::sum);
    return Beat.of(Kind.TAG, plan.script);
  }

  /** "ok calling you": the rest of it happens where R can't see. */
  private Beat takeItOffline(List<Person> around) {
    var ids = threads().stream().map(i -> i.id).toList();
    var said = w.items.values().stream()
        .filter(i -> i.type == Item.Type.COMMENT && i.aired() && ids.contains(i.postId) && !i.authorId.equals(w.userId) && i.time > now - HOUR)
        .sorted(Comparator.comparing((Item i) -> i.id)).toList();
    for (var c : said) {
      var post = w.items.get(c.postId);
      var b = pick(around, x -> !avoids(x, c.authorId) && knows(x, c.authorId));
      if (post == null || b == null) continue;
      plan.comment("chatter-offline:" + b.id + ":" + c.id, lands, post.id, c.id, b.id,
          brief(post, Stance.SUPPORT, c.authorId, null, "a few words taking this somewhere private, like calling or texting them right now, with no hint of what about"));
      used(Kind.OFFLINE, b.id);
      st.saidOn.merge(post.id, 1, Integer::sum);
      return Beat.of(Kind.OFFLINE, plan.script);
    }
    return null;
  }

  /** Meant for a private chat, sent here: a line about something else, and a moment later they notice. */
  Beat wrongChat(List<Person> around) {
    var threads = threads();
    if (threads.isEmpty()) return null;
    var post = threads.get(rng.nextInt(threads.size()));
    var b = pick(around, x -> !avoids(x, post.authorId) && knows(x, post.authorId));
    if (b == null) return null;
    var c = plan.comment("chatter-wrong:" + b.id + ":" + post.id + ":" + now, lands, post.id, null, b.id,
        brief(post, Stance.SUPPORT, null, null, "meant for a private chat and sent here by mistake: a mundane line about their own day, to someone else, nothing to do with this post. Ignore `answering`"));
    plan.comment("chatter-wrong-fix:" + c.id(), lands + 8_000 + rng.nextInt(8_000), post.id, null, b.id,
        brief(post, Stance.SUPPORT, null, null, "realizes that went to the wrong place: a few mortified words, like wrong chat, in their own way, and nothing else"));
    used(Kind.WRONG_CHAT, b.id);
    st.saidOn.merge(post.id, 2, Integer::sum);
    return Beat.of(Kind.WRONG_CHAT, plan.script);
  }

  /** A coworker or relative says what they shouldn't in public: only among real ties. */
  Beat awkward(List<Person> around) {
    for (var post : threads()) {
      if (post.authorId.equals(w.userId)) continue;
      var b = pick(around, x -> !avoids(x, post.authorId) && !post.commenters.contains(x.id) && tie(x, post.authorId) != null);
      if (b == null) continue;
      plan.comment("chatter-awkward:" + b.id + ":" + post.id, lands, post.id, null, b.id,
          brief(post, Stance.JOKE, post.authorId, null, "they're " + tie(b, post.authorId) + ", and in public they say something a bit too familiar or too revealing about the poster's ordinary life, small and harmless, with no idea it's awkward"));
      used(Kind.AWKWARD, b.id);
      st.saidOn.merge(post.id, 1, Integer::sum);
      return Beat.of(Kind.AWKWARD, plan.script);
    }
    return null;
  }

  private String tie(Person b, String other) {
    var e = w.edgeOrNull(b.id, other);
    if (e == null) return null;
    return switch (e.kind) {
      case COWORKER -> "their coworker";
      case FAMILY -> "their relative";
      default -> null;
    };
  }

  /** "@russ settle this": R gets called into an argument that's going right now. */
  Beat pulledIn() {
    var a = st.argument;
    if (a == null) return null;
    var post = w.items.get(a.postId);
    var parent = w.items.get(a.lastComment);
    if (post == null || parent == null || !parent.aired() || post.commenters.contains(w.userId)) return null;
    var who = w.person(a.nextUp);
    var e = w.edgeOrNull(who.id, w.userId);
    if (e == null || !e.connected) return null;
    String other = a.nextUp.equals(a.a) ? a.b : a.a;
    var extra = new java.util.LinkedHashMap<String, Object>(Plan.extra("on", post.id));
    extra.put("mentions", List.of(w.userId));
    var c = plan.comment("chatter-pulled:" + who.id + ":" + a.lastComment, lands, post.id, a.lastComment, who.id,
        new Brief(post.defId, post.category, "tags @" + w.person(w.userId).handle + " to settle this, in a few words. He hasn't said anything here yet", Stance.DISAGREE.name(), other, "debate_exchange",
            post.topics, post.controversy, post.drama, extra));
    a.lastComment = c.id();
    a.nextUp = other;
    a.turnsLeft--;
    used(Kind.PULLED_IN, who.id);
    st.saidOn.merge(post.id, 1, Integer::sum);
    return Beat.of(Kind.PULLED_IN, plan.script);
  }

  /** A friend asks R something practical before he's said a word to them. */
  Beat askedFirst(List<Person> around) {
    var b = pick(around, x -> {
      var e = w.edgeOrNull(x.id, w.userId);
      return e != null && e.connected && e.affinity >= 15;
    });
    if (b == null) return null;
    plan.dm("chatter-ask:" + b.id + ":" + now, lands, b.id, w.userId, new Brief(null, "DM",
        "asks R something practical because they figure he'd know, a good plumber, a mechanic, somewhere to eat, in a line or two. No small talk first", Stance.SUPPORT.name(), w.userId, "dm_warm", List.of(), 0, 0, Plan.extra("beat", "askedFirst")));
    used(Kind.ASKED_FIRST, b.id);
    return Beat.of(Kind.ASKED_FIRST, plan.script);
  }

  /** Two people talking about R on a thread he isn't in. All they can use is what he actually posted. */
  Beat talkedAbout(List<Person> around) {
    String name = w.person(w.userId).name.split(" ")[0];
    String source = hisLines.isEmpty() ? "They have nothing specific, so it stays vague" : "All they know is what he said in public: \"" + hisLines.get(rng.nextInt(hisLines.size())) + "\". If that doesn't fit, it stays vague";
    for (var post : threads()) {
      if (post.authorId.equals(w.userId) || post.commenters.contains(w.userId)) continue;
      var b = pick(around, x -> !avoids(x, post.authorId) && !post.commenters.contains(x.id) && knows(x, post.authorId));
      if (b == null) continue;
      plan.comment("chatter-about:" + b.id + ":" + post.id, lands, post.id, null, b.id,
          brief(post, Stance.JOKE, post.authorId, null, "brings up " + name + " in passing, the way you mention a friend who isn't in the thread, by first name and with no tag. " + source + ". Never invent anything he did"));
      used(Kind.TALKED_ABOUT, b.id);
      st.saidOn.merge(post.id, 1, Integer::sum);
      return Beat.of(Kind.TALKED_ABOUT, plan.script);
    }
    return null;
  }

  /** Second-hand, and true: code picks who, from how they really feel about R. */
  Beat gossip(List<Person> around) {
    var b = pick(around, x -> {
      var e = w.edgeOrNull(x.id, w.userId);
      return e != null && e.connected && e.affinity >= 25;
    });
    if (b == null) return null;
    var who = new ArrayList<Person>();
    for (var x : w.residents()) {
      if (x.id.equals(b.id) || !knows(b, x.id)) continue;
      var e = w.edgeOrNull(x.id, w.userId);
      if (e != null && (e.affinity >= GOSSIP_FAN || e.affinity <= GOSSIP_COOL)) who.add(x);
    }
    if (who.isEmpty()) return null;
    var x = who.get(rng.nextInt(who.size()));
    boolean likes = w.edge(x.id, w.userId).affinity >= GOSSIP_FAN;
    plan.dm("chatter-gossip:" + b.id + ":" + x.id + ":" + now, lands, b.id, w.userId, new Brief(null, "DM",
        "tells R, second-hand, that @" + x.handle + (likes ? " really likes him and has said so" : " isn't exactly his biggest fan and has let it slip") + ". A line or two, no details beyond that, and nothing invented about why", Stance.SUPPORT.name(), w.userId,
        "dm_warm", List.of(), 0, 0, Plan.extra("beat", "gossip")));
    used(Kind.GOSSIP, b.id);
    return Beat.of(Kind.GOSSIP, plan.script);
  }

  /** Something R really said stuck with someone. The words are his, exactly. */
  Beat quoted(List<Person> around) {
    if (hisLines.isEmpty()) return null;
    String line = hisLines.get(rng.nextInt(hisLines.size()));
    for (var post : threads()) {
      if (post.authorId.equals(w.userId)) continue;
      var b = pick(around, x -> !avoids(x, post.authorId) && !post.commenters.contains(x.id) && knows(x, post.authorId));
      if (b == null) continue;
      var extra = new java.util.LinkedHashMap<String, Object>(Plan.extra("on", post.id));
      extra.put("mentions", List.of(w.userId));
      plan.comment("chatter-quoted:" + b.id + ":" + post.id, lands, post.id, null, b.id,
          new Brief(post.defId, post.category, "quotes something @" + w.person(w.userId).handle + " said, word for word, because it stuck with them and it fits here: \"" + line + "\"", Stance.JOKE.name(), post.authorId, "joke_comment",
              post.topics, post.controversy, post.drama, extra));
      used(Kind.QUOTED, b.id);
      st.saidOn.merge(post.id, 1, Integer::sum);
      return Beat.of(Kind.QUOTED, plan.script);
    }
    return null;
  }

  /** Something happening on one block: code picks what, and who lives there. Good for an hour, then forgotten. */
  Beat neighborhood() {
    var table = lib.tables().get("neighborhood-events");
    if (table == null || table.isEmpty()) return null;
    var areas = new TreeMap<String, List<Person>>();
    var byHandle = w.byHandle();
    for (var r : lib.roster().residents()) {
      var p = byHandle.get(r.resolvedHandle());
      if (p == null || r.livesIn() == null) continue;
      String area = r.livesIn().endsWith(", Chicago") ? "Chicago" : r.livesIn();
      areas.computeIfAbsent(area, x -> new ArrayList<>()).add(p);
    }
    var fit = new ArrayList<>(areas.entrySet().stream().filter(x -> x.getValue().size() >= 2 && x.getValue().stream().anyMatch(p -> {
      var e = w.edgeOrNull(p.id, w.userId);
      return e != null && e.connected;
    })).toList());
    if (fit.isEmpty()) return null;
    var pick = fit.get(rng.nextInt(fit.size()));
    var known = pick.getValue().stream().filter(p -> {
      var e = w.edgeOrNull(p.id, w.userId);
      return e != null && e.connected;
    }).toList();
    var a = known.get(rng.nextInt(known.size()));
    String what = String.valueOf(table.get(rng.nextInt(table.size())).get("what"));
    var post = plan.post("chatter-local:" + a.id + ":" + now, lands, a.id, app.radiance.social.PostRow.Kind.STATUS,
        new Brief(null, "DAILY", "right now, where they live in " + pick.getKey() + ": " + what + ". They post what they can see or hear, confused or amused, as it happens", null, null, null, List.of("local"), 0.1, 0.1, Map.of()),
        null, null, null, 2);
    st.local = new Local(post.id(), pick.getValue().stream().map(p -> p.id).toList(), now + HOUR, !pick.getKey().equals("Chicago"));
    used(Kind.NEIGHBORHOOD, a.id);
    return Beat.of(Kind.NEIGHBORHOOD, plan.script);
  }

  private Beat aroundTheBlock() {
    var post = w.items.get(st.local.postId());
    if (post == null || !post.aired()) return null;
    var neighbors = st.local.people().stream().filter(id -> !id.equals(post.authorId) && !post.commenters.contains(id)).map(w::person).toList();
    if (neighbors.isEmpty()) return null;
    var b = neighbors.get(rng.nextInt(neighbors.size()));
    plan.comment("chatter-local-reply:" + b.id + ":" + post.id, lands, post.id, null, b.id,
        brief(post, Stance.SUPPORT, post.authorId, null, st.local.sameBlock() ? "they live there too and they've noticed the same thing, from where they are"
            : "they live elsewhere in the city, so all they know is what this post says; they aren't there and don't claim to know that street"));
    used(Kind.COMMENT, b.id);
    return Beat.of(Kind.COMMENT, plan.script);
  }

  /** A close friend sends a picture of what they're dealing with right now. */
  Beat dmPhoto(List<Person> around) {
    var b = pick(around, x -> {
      var e = w.edgeOrNull(x.id, w.userId);
      return e != null && e.connected && e.affinity >= CLOSE_ENOUGH_FOR_PHOTOS;
    });
    if (b == null) return null;
    var extra = new java.util.LinkedHashMap<String, Object>(Plan.extra("beat", "dmPhoto"));
    extra.put("media", "an amateur smartphone photo of the mess or the thing they're dealing with right now");
    plan.dm("chatter-dm-photo:" + b.id + ":" + now, lands + PHOTO_LEAD, b.id, w.userId, new Brief(null, "DM",
        "sends R a photo of the mess or the thing they're dealing with right now, with a short line to go with it, like look at this", Stance.JOKE.name(), w.userId, "dm_warm", List.of(), 0, 0, extra));
    used(Kind.DM_PHOTO, b.id);
    return Beat.of(Kind.DM_PHOTO, plan.script);
  }

  /** A friend asks who's in for tonight and counts R in. Just the invite: nothing follows up. */
  Beat savedSeat(List<Person> around) {
    int hour = java.time.Instant.ofEpochMilli(now).atZone(w.zone).getHour();
    if (hour < 10 || hour > 20) return null;
    var a = pick(around, x -> {
      var e = w.edgeOrNull(x.id, w.userId);
      return e != null && e.connected && e.affinity >= 25;
    });
    if (a == null) return null;
    var extra = new java.util.LinkedHashMap<String, Object>();
    extra.put("mentions", List.of(w.userId));
    plan.post("chatter-seat:" + a.id + ":" + now, lands, a.id, app.radiance.social.PostRow.Kind.STATUS,
        new Brief(null, "SOCIAL", "something happening tonight that suits them, who's in? And they count @" + w.person(w.userId).handle + " in without asking, in a few words, like saving him a seat", null, null, null, List.of(), 0, 0, extra),
        null, null, null, 2);
    used(Kind.SAVED_SEAT, a.id);
    return Beat.of(Kind.SAVED_SEAT, plan.script);
  }

  /**
   * Drop-in mode: someone goes after a take on the news, and the poster bites. Code picks the fighter, the most
   * argumentative person around who isn't the poster's ex, and the poster answers next as the argument carries on.
   */
  Beat pickFight(List<Person> around) {
    var takes = threads().stream().filter(i -> i.headlineId != null && !i.authorId.equals(w.userId)
        && st.saidOn.getOrDefault(i.id, 0) < THREAD_BURNS_OUT && !Planner.crowded(w, i, 0))
        .sorted(Comparator.comparingDouble((Item i) -> -i.controversy - rng.nextDouble() * 0.3).thenComparing(i -> i.id)).toList();
    for (var post : takes) {
      var author = w.person(post.authorId);
      if (author == null) continue;
      var b = around.stream().filter(x -> !avoids(x, post.authorId) && !post.commenters.contains(x.id))
          .max(Comparator.comparingDouble((Person x) -> x.trait("argumentativeness") - x.trait("agreeableness") / 2 + 30 * rng.nextDouble()).thenComparing(x -> x.id)).orElse(null);
      if (b == null) continue;
      var stance = b.trait("agreeableness") < 40 && rng.chance(0.5) ? Stance.MOCK : Stance.DISAGREE;
      var c = plan.comment("chatter-fight:" + b.id + ":" + post.id, lands, post.id, null, b.id,
          brief(post, stance, post.authorId, "debate_exchange", "goes after this take: says plainly why it's wrong, in their own voice, and doesn't hedge"));
      st.argument = new Argument(post.id, b.id, post.authorId, c.id(), 5 + rng.nextInt(5));
      used(Kind.PICKED_FIGHT, b.id);
      st.lastOf.put(Kind.ARGUMENT, now);
      st.saidOn.merge(post.id, 1, Integer::sum);
      return Beat.of(Kind.PICKED_FIGHT, plan.script);
    }
    return null;
  }

  /**
   * Drop-in mode: someone takes issue with something R just said in public, and tells him so in a DM. The words they
   * quote are his, exactly; code picks who, the people likeliest to argue and least fond of him first.
   */
  Beat calledOut(List<Person> around) {
    var lines = st.heSaidLately.stream().filter(x -> !st.calledOutLines.contains(x)).toList();
    if (lines.isEmpty() || w.userId == null) return null;
    String line = lines.get(rng.nextInt(lines.size()));
    var pool = new ArrayList<Person>(around);
    for (var p : w.residents()) if (!pool.contains(p) && w.edgeOrNull(p.id, w.userId) != null) pool.add(p);
    var b = pool.stream().filter(x -> !st.calledOutBy.contains(x.id) && w.edgeOrNull(x.id, w.userId) != null && !avoids(x, w.userId))
        .max(Comparator.comparingDouble((Person x) -> x.trait("argumentativeness") - x.trait("agreeableness") / 2
            - w.edge(x.id, w.userId).affinity / 2 + 30 * rng.nextDouble()).thenComparing(x -> x.id)).orElse(null);
    if (b == null) return null;
    var stance = b.trait("agreeableness") < 40 ? Stance.MOCK : Stance.DISAGREE;
    var extra = new java.util.LinkedHashMap<String, Object>(Plan.extra("beat", "calledOut"));
    extra.put("said", line);
    plan.dm("chatter-called-out:" + b.id + ":" + now, lands, b.id, w.userId, new Brief(null, "DM",
        "messages R out of nowhere to pick a fight about something he said in public: \"" + line + "\". They quote or point at it and tell him why he's wrong or out of line, sharp and a bit heated, in their own voice, no greeting. Nothing else he said is known to them",
        stance.name(), w.userId, "dm_curt", List.of(), 0, 0, extra));
    st.calledOutLines.add(line);
    st.calledOutBy.add(b.id);
    used(Kind.CALLED_OUT, b.id);
    return Beat.of(Kind.CALLED_OUT, plan.script);
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
