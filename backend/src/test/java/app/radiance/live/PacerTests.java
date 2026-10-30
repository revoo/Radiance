package app.radiance.live;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.content.ClasspathContent;
import app.radiance.content.Rules.PacingRules;
import app.radiance.kernel.RandomSource;
import app.radiance.live.Pacer.Candidate;
import app.radiance.live.Pacer.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The Pacer's rules against a fake clock (SPEC §5.1, §10): curves, buckets, priority, breathing room, hooks. */
class PacerTests {

  static final PacingRules RULES = ClasspathContent.load(List.of()).pacing();
  static final long T0 = 1_800_000_000_000L;

  static Pacer pacer(long seed) {
    return new Pacer(RULES, RandomSource.seeded(seed).derive("pacer"));
  }

  static List<Candidate> crowd(String trigger, int reactions, int comments) {
    var out = new ArrayList<Candidate>();
    for (int i = 0; i < reactions; i++) {
      out.add(new Candidate(trigger + ":r" + i, Kind.REACTION, "a" + i, trigger, (i % 10) / 10.0, 0.5, 0.2, false));
    }
    for (int i = 0; i < comments; i++) {
      out.add(new Candidate(trigger + ":c" + i, Kind.COMMENT, "b" + i, trigger, (i % 7) / 7.0, 0.5, 0.2, false));
    }
    return out;
  }

  /** Runs the release loop second by second; returns release times by kind. */
  static TreeMap<Long, List<Candidate>> run(Pacer p, long from, long to) {
    var out = new TreeMap<Long, List<Candidate>>();
    for (long t = from; t <= to; t += 250) {
      var due = p.due(t);
      if (!due.isEmpty()) out.put(t, due);
    }
    return out;
  }

  @Test
  void firstReactionWithin6sFirstCommentWithin25sAndTaperingWithin5Minutes() {
    for (long seed = 1; seed <= 50; seed++) {
      var p = pacer(seed);
      p.plan("post", T0, crowd("post", 15, 8), false, false);
      run(p, T0, T0 + 10 * 60_000);
      var b = p.burst("post");
      assertThat(b.firstReaction() - T0).as("first reaction, seed " + seed).isBetween(2_000L, 6_000L);
      assertThat(b.firstComment() - T0).as("first comment, seed " + seed).isBetween(8_000L, 25_000L);
      assertThat(b.lastRelease() - T0).as("taper, seed " + seed).isLessThanOrEqualTo(5 * 60_000L);
      assertThat(b.comments()).isGreaterThanOrEqualTo(4);
    }
  }

  @Test
  void neverMoreThanSixCommentsOrTwelveReactionsInAnyMinute() {
    var p = pacer(3);
    // R posts three times in a row and replies twice: far more responders than the limits allow
    for (int i = 0; i < 5; i++) p.plan("p" + i, T0 + i * 5_000, crowd("p" + i, 20, 10), true, false);
    var releases = run(p, T0, T0 + 20 * 60_000);
    var comments = new ArrayList<Long>();
    var reactions = new ArrayList<Long>();
    releases.forEach((t, cs) -> cs.forEach(c -> (c.kind() == Kind.COMMENT ? comments : reactions).add(t)));
    assertThat(maxInAnyMinute(comments)).isLessThanOrEqualTo(6);
    assertThat(maxInAnyMinute(reactions)).isLessThanOrEqualTo(12);
    assertThat(comments).isNotEmpty();
  }

  @Test
  void notificationsAreLimitedGroupedAndPauseAfterABurst() {
    var p = pacer(5);
    var delivered = new ArrayList<Long>();
    for (int i = 0; i < 30; i++) p.notify(new Pacer.Note("n" + i, i % 3 == 0 ? "COMMENT" : "REACTION", "a" + i, "POST",
        "post" + (i % 4), T0));
    int groups = 0;
    for (long t = T0; t < T0 + 10 * 60_000; t += 500) {
      for (var d : p.deliveries(t)) {
        delivered.add(t);
        if (d.actorIds().size() > 1) groups++;
      }
    }
    assertThat(maxInAnyMinute(delivered)).isLessThanOrEqualTo(4);
    assertThat(groups).as("reactions on one post are grouped").isPositive();

    // three releases inside 30 s start a breathing pause of 20–40 s for notifications
    var q = pacer(6);
    var three = List.of(new Candidate("x1", Kind.REACTION, "a", "x", 1, 1, 0, false),
        new Candidate("x2", Kind.REACTION, "b", "x", 1, 1, 0, false),
        new Candidate("x3", Kind.REACTION, "c", "x", 1, 1, 0, false));
    q.plan("x", T0, three, false, false);
    long now = T0 + 29_000;
    assertThat(q.due(now)).hasSize(3);
    Long until = q.snapshot(now).breathingUntil();
    assertThat(until).isNotNull();
    assertThat(until - now).isBetween(20_000L, 40_000L);
    q.notify(new Pacer.Note("late", "COMMENT", "z", "POST", "x", now));
    assertThat(q.deliveries(now + 1_000)).isEmpty();
    assertThat(q.deliveries(until)).hasSize(1);
  }

  @Test
  void highestPriorityGoesFirstAndOldReactionsAreDropped() {
    var p = pacer(7);
    var low = new Candidate("low", Kind.COMMENT, "a", "post", 0.0, 0.0, 0.0, false);
    var high = new Candidate("high", Kind.COMMENT, "b", "post", 1.0, 1.0, 1.0, false);
    p.plan("post", T0, List.of(low, high), false, false);
    assertThat(p.desiredAt("high")).isLessThan(p.desiredAt("low"));

    var q = pacer(8);
    q.plan("flood", T0, crowd("flood", 60, 0), false, false);
    run(q, T0, T0 + 30 * 60_000);
    var b = q.burst("flood");
    assertThat(b.reactions() + b.dropped()).isEqualTo(60);
  }

  @Test
  void theAddresseeAnswersFirstAndPileOnIsCapped() {
    var p = pacer(9);
    var items = new ArrayList<Candidate>();
    items.add(new Candidate("other1", Kind.COMMENT, "a", "post", 1, 1, 1, false));
    items.add(new Candidate("addressee", Kind.COMMENT, "b", "post", 0, 0, 0, true));
    items.add(new Candidate("other2", Kind.COMMENT, "c", "post", 1, 1, 1, false));
    items.add(new Candidate("other3", Kind.COMMENT, "d", "post", 1, 1, 1, false));
    var unpaced = p.plan("reply", T0, items, false, true);
    assertThat(p.desiredAt("addressee") - T0).isBetween(5_000L, 20_000L);
    assertThat(p.desiredAt("other1")).isGreaterThan(p.desiredAt("addressee"));
    assertThat(unpaced).hasSize(1);
  }

  @Test
  void aHookOnlyAfterTheQuietGapAndAtMostEveryTenMinutes() {
    var p = pacer(10);
    p.seen(T0);
    assertThat(p.hookDue(T0 + 3 * 60_000)).isFalse();
    assertThat(p.hookDue(T0 + 4 * 60_000)).isTrue();
    p.hooked(T0 + 4 * 60_000);
    assertThat(p.hookDue(T0 + 9 * 60_000)).isFalse();
    assertThat(p.hookDue(T0 + 14 * 60_000)).isTrue();
  }

  @Test
  void theSameSeedReplaysTheSameSession() {
    var a = pacer(11);
    var b = pacer(11);
    a.plan("post", T0, crowd("post", 10, 5), false, false);
    b.plan("post", T0, crowd("post", 10, 5), false, false);
    assertThat(run(a, T0, T0 + 600_000).keySet()).isEqualTo(run(b, T0, T0 + 600_000).keySet());
  }

  @Test
  void dmTimingFollowsPacingYaml() {
    var p = pacer(12);
    for (int i = 0; i < 200; i++) {
      assertThat(p.dmTypingStart()).isBetween(1_000L, 3_000L);
      assertThat(p.dmBurstGap()).isBetween(2_000L, 5_000L);
    }
    assertThat(p.dmTypingDelay(0)).isZero();
    assertThat(p.dmTypingDelay(50)).isEqualTo(1_500);
    assertThat(p.dmTypingDelay(10_000)).as("capped").isEqualTo(6_000);
  }

  static int maxInAnyMinute(List<Long> times) {
    int max = 0;
    for (int i = 0; i < times.size(); i++) {
      int n = 0;
      for (long t : times) if (t >= times.get(i) && t < times.get(i) + 60_000) n++;
      max = Math.max(max, n);
    }
    return max;
  }
}
