package app.radiance.live;

import app.radiance.content.Durations;
import app.radiance.content.Rules.Bucket;
import app.radiance.content.Rules.PacingRules;
import app.radiance.kernel.RandomSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * When live items aimed at R become visible (SPEC §5.1, A.6). The engagement model has already decided
 * who responds and how; the Pacer only decides when: response curves after R posts or replies, token
 * buckets with a hard per-minute cap, priority release, breathing room for notifications, and hooks
 * against dead air. Pure and single-threaded (the caller serializes): timing comes from its own seeded
 * stream and every method takes the time, so a session replays exactly.
 */
public final class Pacer {

  public enum Kind { REACTION, COMMENT }

  /**
   * One live item aimed at R. {@code closeness} and {@code relevance} are 0–1, {@code drama} 0–1;
   * {@code addressee} is the person R replied to, who answers first.
   */
  public record Candidate(String key, Kind kind, String actorId, String postId, double closeness, double relevance,
      double drama, boolean addressee) {}

  /** A notification waiting to reach R; reactions on the same target are grouped into one delivery. */
  public record Note(String id, String kind, String actorId, String refType, String refId, long createdAt) {}

  /** What reaches R at once: one notification, or a group of reactions ("Sarah and 3 others reacted"). */
  public record Delivery(String kind, String refType, String refId, List<String> actorIds, List<String> noteIds,
      long at) {}

  /** One response burst (after one of R's posts or replies), for the pacing monitor. */
  public record BurstStats(String trigger, long start, Long firstReaction, Long firstComment, Long lastRelease,
      int reactions, int comments, int dropped, int unpaced) {}

  /** The pacing monitor's view of the session. */
  public record Snapshot(long now, int pending, int commentsLastMinute, int reactionsLastMinute,
      int notificationsLastMinute, int maxCommentsPerMinute, int maxNotificationsPerMinute, Long breathingUntil,
      Long lastSeen, Long lastHook, List<BurstStats> bursts) {}

  private static final long MINUTE = 60_000L;

  private final RandomSource rng;
  private final Map<String, Double> weights;
  private final long[] reflexFirst, reflexSpacing, commentFirst, addresseeFirst, breathingPause, dmTypingStarts, dmBurstGap;
  private final double gapGrowth, dramaTail;
  private final long commentTail, reactionHold, breathingWithin, quietGap, hookInterval, typingGiveUp, dmMaxTyping;
  private final int dmMsPerChar;
  private final int maxPileOn, breathingAfter;
  private final Gate comments, reactions, notifications;
  private final boolean groupReactions;

  private final List<Waiting> pending = new ArrayList<>();
  private final Map<String, Burst> bursts = new LinkedHashMap<>();
  private final Deque<Long> recentReleases = new ArrayDeque<>();
  private final List<Note> notes = new ArrayList<>();
  private long breathingUntil = Long.MIN_VALUE;
  private long lastSeen = Long.MIN_VALUE;
  private long lastHook = Long.MIN_VALUE;
  private int maxCommentsPerMinute, maxNotificationsPerMinute;

  public Pacer(PacingRules rules, RandomSource rng) {
    this.rng = rng;
    var rc = rules.responseCurve();
    this.weights = rules.priorityWeights();
    this.reflexFirst = range(rc.reflexReaction().first());
    this.reflexSpacing = range(rc.reflexReaction().spacing());
    this.commentFirst = range(rc.comment().first());
    this.gapGrowth = rc.comment().gapGrowth();
    this.commentTail = ms(rc.comment().tail());
    this.addresseeFirst = range(rc.replyToR().addresseeFirst());
    this.maxPileOn = rc.replyToR().maxPileOn();
    this.dramaTail = rc.dramaTailMultiplier();
    this.comments = new Gate(rules.buckets().get("comments"));
    this.reactions = new Gate(rules.buckets().get("reactions"));
    var nb = rules.buckets().get("notifications");
    this.notifications = new Gate(nb);
    this.groupReactions = nb == null || nb.groupReactions() == null || nb.groupReactions();
    String hold = rules.hold().getOrDefault("reactions_max", "2m");
    this.reactionHold = ms(hold);
    var br = rules.breathingRoom();
    this.breathingAfter = br.afterReleases();
    this.breathingWithin = ms(br.within());
    this.breathingPause = range(br.pause());
    this.quietGap = ms(rules.hooks().quietGap());
    this.hookInterval = ms(rules.hooks().minInterval());
    this.typingGiveUp = ms(rules.typing().giveUpAfter());
    var dm = rules.dm();
    this.dmTypingStarts = range(dm.typingStarts());
    this.dmBurstGap = range(dm.burstGap());
    this.dmMsPerChar = dm.msPerChar();
    this.dmMaxTyping = ms(dm.maxTypingDelay());
  }

  // ------------------------------------------------------------------------------------------ planning

  /**
   * Times the responses to R's post (or comment) {@code trigger}, made at {@code start}. Call it once for
   * the reflex phase and again for the considered phase; the curves continue where the first call left
   * off. Returns the keys left unpaced (past the tail, or pile-on beyond the cap): they keep the
   * resident's own timing and air as background.
   */
  public List<String> plan(String trigger, long start, List<Candidate> items, boolean dramatic, boolean reply) {
    var b = bursts.computeIfAbsent(trigger, k -> new Burst(k, start, dramatic, reply));
    var sorted = new ArrayList<>(items);
    sorted.sort(Comparator.comparing((Candidate c) -> !c.addressee()).thenComparing(c -> -priority(c))
        .thenComparing(Candidate::key));
    var unpaced = new ArrayList<String>();
    long tail = b.start + (long) (commentTail * (b.dramatic ? dramaTail : 1));
    for (var c : sorted) {
      long at;
      if (c.kind() == Kind.REACTION) {
        at = b.nextReaction == Long.MIN_VALUE ? b.start + draw(reflexFirst) : b.nextReaction + draw(reflexSpacing);
        b.nextReaction = at;
      } else {
        if (b.reply && !c.addressee() && b.pileOn >= maxPileOn) {
          unpaced.add(c.key());
          b.unpaced++;
          continue;
        }
        if (b.nextComment == Long.MIN_VALUE) {
          at = b.start + draw(c.addressee() ? addresseeFirst : commentFirst);
          b.gap = Math.max(4_000, draw(commentFirst) / 2);
        } else {
          at = b.nextComment + b.gap;
          b.gap = (long) (b.gap * gapGrowth);
        }
        if (at > tail) {
          unpaced.add(c.key());
          b.unpaced++;
          continue;
        }
        if (b.reply && !c.addressee()) b.pileOn++;
        b.nextComment = at;
      }
      pending.add(new Waiting(c, at, b));
    }
    return unpaced;
  }

  /** The desired time of a planned item, or null if it isn't waiting (for tests and the monitor). */
  public Long desiredAt(String key) {
    return pending.stream().filter(w -> w.c.key().equals(key)).map(w -> w.at).findFirst().orElse(null);
  }

  // ------------------------------------------------------------------------------------------- release

  /**
   * The items to release at {@code now}, highest priority first. Reactions held longer than the maximum
   * hold are dropped (returned by {@link #dropped}); comments wait for the next slot.
   */
  public List<Candidate> due(long now) {
    var ready = pending.stream().filter(w -> w.at <= now)
        .sorted(Comparator.comparing((Waiting w) -> -priority(w.c)).thenComparingLong(w -> w.at)
            .thenComparing(w -> w.c.key()))
        .toList();
    var out = new ArrayList<Candidate>();
    for (var w : ready) {
      var gate = w.c.kind() == Kind.COMMENT ? comments : reactions;
      if (gate.take(now)) {
        pending.remove(w);
        out.add(w.c);
        released(w, now);
      } else if (w.c.kind() == Kind.REACTION && now - w.at > reactionHold) {
        pending.remove(w);
        w.burst.dropped++;
        droppedKeys.add(w.c.key());
      }
    }
    maxCommentsPerMinute = Math.max(maxCommentsPerMinute, comments.inLastMinute(now));
    return out;
  }

  private final List<String> droppedKeys = new ArrayList<>();

  /** Keys dropped since the last call (reactions held past the maximum hold). */
  public List<String> dropped() {
    var out = List.copyOf(droppedKeys);
    droppedKeys.clear();
    return out;
  }

  /** Forget a planned item (its render failed, or it was dropped at airing). */
  public void cancel(String key) {
    pending.removeIf(w -> w.c.key().equals(key));
  }

  private void released(Waiting w, long now) {
    var b = w.burst;
    if (w.c.kind() == Kind.REACTION) {
      b.reactions++;
      if (b.firstReaction == null) b.firstReaction = now;
    } else {
      b.comments++;
      if (b.firstComment == null) b.firstComment = now;
    }
    b.lastRelease = now;
    seen(now);
    recentReleases.addLast(now);
    while (!recentReleases.isEmpty() && recentReleases.peekFirst() < now - breathingWithin) recentReleases.removeFirst();
    if (recentReleases.size() >= breathingAfter) {
      // after a burst, notifications pause so R can read
      breathingUntil = now + draw(breathingPause);
      recentReleases.clear();
    }
  }

  // ------------------------------------------------------------------------------------- notifications

  /** A notification created when its item aired; it reaches R when the limits allow. */
  public void notify(Note n) {
    notes.add(n);
  }

  /** The notifications that reach R at {@code now}, reactions on one target grouped into one delivery. */
  public List<Delivery> deliveries(long now) {
    var out = new ArrayList<Delivery>();
    if (notes.isEmpty() || now < breathingUntil) return out;
    while (!notes.isEmpty() && notifications.take(now)) {
      var first = notes.removeFirst();
      var actors = new ArrayList<>(List.of(first.actorId()));
      var ids = new ArrayList<>(List.of(first.id()));
      if (groupReactions && first.kind().equals("REACTION")) {
        var it = notes.iterator();
        while (it.hasNext()) {
          var n = it.next();
          if (n.kind().equals("REACTION") && n.refId().equals(first.refId())) {
            if (!actors.contains(n.actorId())) actors.add(n.actorId());
            ids.add(n.id());
            it.remove();
          }
        }
      }
      out.add(new Delivery(first.kind(), first.refType(), first.refId(), actors, ids, now));
    }
    maxNotificationsPerMinute = Math.max(maxNotificationsPerMinute, notifications.inLastMinute(now));
    return out;
  }

  // ---------------------------------------------------------------------------------------------- hooks

  /** Something new reached R (a release, new posts behind the pill, a delivered notification). */
  public void seen(long now) {
    lastSeen = Math.max(lastSeen, now);
  }

  /** R has seen nothing new for the quiet gap, and no hook surfaced in the last {@code min_interval}. */
  public boolean hookDue(long now) {
    if (lastSeen == Long.MIN_VALUE) lastSeen = now;
    return pending.isEmpty() && now - lastSeen >= quietGap && (lastHook == Long.MIN_VALUE || now - lastHook >= hookInterval);
  }

  public void hooked(long now) {
    lastHook = now;
    lastSeen = now;
  }

  // ----------------------------------------------------------------------------------------------- DMs

  /**
   * Messenger timing (SPEC §5.11, A.6 {@code dm}): typing dots start this long after R's message lands.
   * DMs aren't rate-limited like comments: anyone R talks to answers within seconds.
   */
  public long dmTypingStart() {
    return draw(dmTypingStarts);
  }

  /** How long a reply of {@code chars} characters is "typed" after it's written: ~30 ms a character, capped. */
  public long dmTypingDelay(int chars) {
    return Math.min(dmMaxTyping, (long) Math.max(0, chars) * dmMsPerChar);
  }

  /** The pause before the next message of a burst ("wait" … "actually"). */
  public long dmBurstGap() {
    return draw(dmBurstGap);
  }

  // -------------------------------------------------------------------------------------------- timing

  /** When the caller should next call {@link #due} / {@link #deliveries}, at the latest. */
  public long nextWake(long now) {
    long next = now + 1_000;
    for (var w : pending) next = Math.min(next, Math.max(now + 50, w.at));
    if (!notes.isEmpty() && breathingUntil > now) next = Math.min(next, breathingUntil);
    return next;
  }

  /** How long a typing indicator may show before it gives up, like a deleted draft. */
  public long typingGiveUp() {
    return typingGiveUp;
  }

  public Snapshot snapshot(long now) {
    var stats = new ArrayList<BurstStats>();
    var list = new ArrayList<>(bursts.values());
    for (var b : list.subList(Math.max(0, list.size() - 20), list.size())) {
      stats.add(new BurstStats(b.trigger, b.start, b.firstReaction, b.firstComment, b.lastRelease, b.reactions,
          b.comments, b.dropped, b.unpaced));
    }
    return new Snapshot(now, pending.size(), comments.inLastMinute(now), reactions.inLastMinute(now),
        notifications.inLastMinute(now), maxCommentsPerMinute, maxNotificationsPerMinute,
        breathingUntil > now ? breathingUntil : null, lastSeen == Long.MIN_VALUE ? null : lastSeen,
        lastHook == Long.MIN_VALUE ? null : lastHook, stats);
  }

  public BurstStats burst(String trigger) {
    var b = bursts.get(trigger);
    return b == null ? null : new BurstStats(b.trigger, b.start, b.firstReaction, b.firstComment, b.lastRelease,
        b.reactions, b.comments, b.dropped, b.unpaced);
  }

  // ------------------------------------------------------------------------------------------ internals

  double priority(Candidate c) {
    return weights.getOrDefault("closeness_to_r", 1.0) * c.closeness()
        + weights.getOrDefault("relevance", 1.0) * c.relevance()
        + weights.getOrDefault("drama", 0.5) * c.drama();
  }

  private long draw(long[] range) {
    return range[0] == range[1] ? range[0] : range[0] + (long) (rng.nextDouble() * (range[1] - range[0]));
  }

  private static long ms(String d) {
    return Durations.parse(d).toMillis();
  }

  private static long[] range(List<String> r) {
    return new long[] {ms(r.get(0)), ms(r.get(r.size() - 1))};
  }

  private record Waiting(Candidate c, long at, Burst burst) {}

  private static final class Burst {
    final String trigger;
    final long start;
    final boolean dramatic, reply;
    long nextReaction = Long.MIN_VALUE, nextComment = Long.MIN_VALUE, gap;
    int pileOn, reactions, comments, dropped, unpaced;
    Long firstReaction, firstComment, lastRelease;

    Burst(String trigger, long start, boolean dramatic, boolean reply) {
      this.trigger = trigger;
      this.start = start;
      this.dramatic = dramatic;
      this.reply = reply;
    }
  }

  /**
   * A token bucket (burst capacity, refilled at {@code per_minute}) plus a hard cap: never more than
   * {@code per_minute} releases in any 60 seconds, which a bucket alone can't promise.
   */
  private static final class Gate {
    final double capacity, perMs;
    final int perMinute;
    double tokens;
    long last = Long.MIN_VALUE;
    final Deque<Long> window = new ArrayDeque<>();

    Gate(Bucket b) {
      this.perMinute = b == null ? Integer.MAX_VALUE : b.perMinute();
      this.capacity = b == null ? Double.MAX_VALUE : Math.max(1, b.burst());
      this.perMs = b == null ? 1 : b.perMinute() / (double) MINUTE;
      this.tokens = capacity;
    }

    boolean take(long now) {
      if (last != Long.MIN_VALUE) tokens = Math.min(capacity, tokens + (now - last) * perMs);
      last = now;
      while (!window.isEmpty() && window.peekFirst() <= now - MINUTE) window.removeFirst();
      if (tokens < 1 || window.size() >= perMinute) return false;
      tokens -= 1;
      window.addLast(now);
      return true;
    }

    int inLastMinute(long now) {
      return (int) window.stream().filter(t -> t > now - MINUTE).count();
    }
  }
}
