package app.radiance.live;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.graph.Onboarding;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.LlmRequest;
import app.radiance.llm.LlmResult;
import app.radiance.llm.OfflineGateway;
import app.radiance.llm.PromptBudget;
import app.radiance.llm.Purpose;
import app.radiance.narrative.FakeRender;
import app.radiance.persistence.TestDatabase;
import app.radiance.sim.Knobs;
import app.radiance.sim.UserActions;
import app.radiance.sim.WorldEngine;
import app.radiance.world.genesis.SeedImporter;
import app.radiance.world.genesis.SkeletonGenerator;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live play end to end on the seed-7 world (SPEC §12 M7 AC), offline models and a manual clock: R posts
 * and the Pacer's timing holds; a late render shows a typing indicator; with no script at all, the first
 * request starts a background backfill instead of waiting on one.
 */
class LivePlayTests {

  static final ContentLibrary LIB = ClasspathContent.load(List.of(
      new ContentProperties.BlacklistEntry("pregnancy", List.of("pregnant"))));

  @TempDir Path dir;
  TestDatabase db;
  WorldEngine engine;
  final List<Timed> events = new CopyOnWriteArrayList<>();
  final AtomicBoolean backfillStarted = new AtomicBoolean();

  record Timed(long at, LivePlay.LiveEvent e) {}

  @BeforeEach
  void world() {
    db = TestDatabase.leased(dir);
    var seed = SkeletonGenerator.generate(LIB, new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
    new SeedImporter(db.sql, db.lane, LIB).importSeed(seed, db.clock.nowMs());
    new Onboarding(db.sql, db.lane, gateway(null), LIB, 1.0).onboard(new Onboarding.Profile("Sam Rivera", "Engineer, runner.",
        "Acme", "Logan Square", List.of("softball"), List.of("tech", "fitness"), Onboarding.Backstory.LOCAL), 7,
        db.clock.nowMs());
    engine = new WorldEngine(db.sql, db.lane, LIB, Knobs.defaults());
    db.clock.advance(Duration.ofMinutes(5)); // time has moved on since the import; no script covers it
  }

  @AfterEach
  void close() {
    db.close();
  }

  /** Offline models; renders wait on {@code gate} when one is given (a slow provider). */
  static LlmGateway gateway(CountDownLatch gate) {
    var fakes = new FakeResponses();
    FakeRender.register(fakes);
    Onboarding.registerFake(fakes);
    Appraiser.registerFake(fakes);
    var offline = OfflineGateway.create(fakes, app.radiance.kernel.WallClock.system());
    if (gate == null) return offline;
    return new LlmGateway() {
      @Override
      public <T> LlmResult<T> call(LlmRequest<T> request) {
        if (request.purpose() == Purpose.RENDER_THREAD) {
          try {
            gate.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        return offline.call(request);
      }

      @Override
      public <T> List<LlmResult<T>> callAll(List<LlmRequest<T>> requests) {
        return requests.stream().map(this::call).toList();
      }

      @Override
      public PromptBudget budgetFor(Purpose purpose) {
        return offline.budgetFor(purpose);
      }

      @Override
      public int degradationLevel() {
        return 0;
      }
    };
  }

  LivePlay live(LlmGateway gateway) {
    var actions = new UserActions(db.sql, db.lane, engine);
    var backfill = new LivePlay.Backfill() {
      @Override
      public boolean running() {
        return false;
      }

      @Override
      public void start() {
        backfillStarted.set(true);
      }
    };
    var live = new LivePlay(db.sql, db.lane, engine, actions, new Appraiser(gateway, Duration.ofSeconds(5)), gateway,
        db.clock, db.holder, backfill, "Radiance", true, Duration.ofMinutes(10)).manualLoop();
    live.addListener(e -> events.add(new Timed(db.clock.nowMs(), e)));
    return live;
  }

  /** Runs the loop on the manual clock in 250 ms steps, giving the virtual threads real time to work. */
  void play(LivePlay live, Duration d) throws InterruptedException {
    long end = db.clock.nowMs() + d.toMillis();
    long lastBeat = db.clock.nowMs();
    while (db.clock.nowMs() < end) {
      db.clock.advance(Duration.ofMillis(250));
      if (db.clock.nowMs() - lastBeat >= 30_000) {
        live.heartbeat();
        lastBeat = db.clock.nowMs();
      }
      live.tick();
      Thread.sleep(2);
    }
  }

  List<Timed> named(String name, long since) {
    return events.stream().filter(t -> t.e().name().equals(name) && t.at() >= since).toList();
  }

  static final List<String> POSTS = List.of(
      "Finally ran my first 10k along the lakefront this morning. Legs are jelly but so worth it!",
      "Hot take: Chicago deep dish is a casserole and I will not be taking questions.",
      "Anyone know a good mechanic near Logan Square? My car is making a horrible noise.",
      "Tried that new ramen place in Wicker Park. Absolutely worth the wait, ask for extra chili oil.",
      "Long week at work. Shipping the release tonight and then sleeping for a day.");

  @Test
  void afterRPostsTheFirstReactionComesWithin6sTheFirstCommentWithin25sAndItTapers() throws Exception {
    var live = live(gateway(null));
    live.touch();
    assertThat(backfillStarted).as("no script covers now: a background backfill").isTrue();
    String userId = engine.world().userId;
    long t0 = db.clock.nowMs();
    var ids = new ArrayList<String>();
    for (String text : POSTS) {
      ids.add(live.post(text));
      play(live, Duration.ofMinutes(7));
    }

    int withComments = 0;
    for (String postId : ids) {
      var burst = live.monitor().pacer().bursts().stream().filter(b -> b.trigger().equals(postId)).findFirst()
          .orElseThrow();
      if (burst.firstReaction() != null) {
        assertThat(burst.firstReaction() - burst.start()).as("first reaction").isLessThanOrEqualTo(6_000);
      }
      if (burst.firstComment() != null) {
        withComments++;
        assertThat(burst.firstComment() - burst.start()).as("first comment").isLessThanOrEqualTo(25_000);
        // the stream saw it land: rendered, aired and announced
        assertThat(named("comment.aired", burst.start()).stream()
            .anyMatch(t -> t.e().data().get("postId").equals(postId))).isTrue();
      }
      if (burst.lastRelease() != null) {
        assertThat(burst.lastRelease() - burst.start()).as("responses taper within about 5 minutes")
            .isLessThanOrEqualTo(5 * 60_000 + 30_000);
      }
    }
    var reactions = named("reaction.changed", t0).stream().filter(t -> !t.e().data().get("actorId").equals(userId)).toList();
    assertThat(reactions).as("residents react").isNotEmpty();
    assertThat(withComments).as("residents comment on some of R's posts").isPositive();

    var comments = named("comment.aired", t0).stream().filter(t -> !t.e().data().get("authorId").equals(userId)).toList();
    assertThat(maxPerMinute(comments)).as("comments per minute").isLessThanOrEqualTo(6);
    assertThat(maxPerMinute(named("notification.created", t0))).as("notifications per minute").isLessThanOrEqualTo(4);
    // nothing visible is ahead of committed time
    long ahead = db.sql.queryOne("SELECT COUNT(*) AS n FROM comment WHERE air_status = 'AIRED' AND created_at > ?",
        List.of(engine.world().time), r -> r.lng("n")).orElseThrow();
    assertThat(ahead).isZero();
  }

  @Test
  void aLateRenderShowsATypingIndicatorNeverAGap() throws Exception {
    var gate = new CountDownLatch(1);
    var live = live(gateway(gate));
    live.touch();
    long t0 = db.clock.nowMs();
    String postId = null;
    for (String text : POSTS) {
      String id = live.post(text);
      play(live, Duration.ofSeconds(40));
      if (named("typing.started", t0).stream().anyMatch(t -> t.e().data().get("postId").equals(id))) {
        postId = id;
        break;
      }
    }
    assertThat(postId).as("a comment's slot came before its render").isNotNull();
    assertThat(live.typingOn(postId)).isNotEmpty();
    String p = postId;
    assertThat(named("comment.aired", t0).stream().filter(t -> t.e().data().get("postId").equals(p))).isEmpty();

    gate.countDown();
    // the render lands: the comment airs and its indicator stops
    for (int i = 0; i < 400 && named("comment.aired", t0).isEmpty(); i++) play(live, Duration.ofMillis(250));
    var aired = named("comment.aired", t0).stream().filter(t -> t.e().data().get("postId").equals(p)).toList();
    assertThat(aired).isNotEmpty();
    var stopped = named("typing.stopped", t0);
    assertThat(stopped).isNotEmpty();
    assertThat(stopped.getFirst().at()).isLessThanOrEqualTo(aired.getFirst().at());
  }

  @Test
  void rsReactionsAndCommentsAreEchoedAtOnce() {
    var live = live(gateway(null));
    live.touch();
    String userId = engine.world().userId;
    String postId = live.post("Rainy Saturday. Coffee, a book and nowhere to be.");
    var row = db.sql.queryOne("SELECT air_status, render_status, body FROM post WHERE id = ?", List.of(postId),
        r -> r.str("air_status") + " " + r.str("render_status")).orElseThrow();
    assertThat(row).isEqualTo("AIRED RENDERED");
    var other = db.sql.queryOne("SELECT id FROM post WHERE air_status = 'AIRED' AND author_id <> ? LIMIT 1", List.of(userId),
        r -> r.str("id"));
    if (other.isPresent()) {
      assertThat(live.react(app.radiance.social.ReactionRow.Target.POST, other.get(), app.radiance.social.ReactionRow.Kind.LOVE))
          .isTrue();
      assertThat(db.sql.queryOne("SELECT kind, air_status FROM reaction WHERE target_id = ? AND actor_id = ?",
          List.of(other.get(), userId), r -> r.str("kind") + " " + r.str("air_status")).orElseThrow()).isEqualTo("LOVE AIRED");
      live.react(app.radiance.social.ReactionRow.Target.POST, other.get(), app.radiance.social.ReactionRow.Kind.HAHA);
      assertThat(db.sql.queryOne("SELECT kind FROM reaction WHERE target_id = ? AND actor_id = ?",
          List.of(other.get(), userId), r -> r.str("kind")).orElseThrow()).isEqualTo("HAHA");
      live.unreact(app.radiance.social.ReactionRow.Target.POST, other.get());
      assertThat(db.sql.queryOne("SELECT kind FROM reaction WHERE target_id = ? AND actor_id = ?",
          List.of(other.get(), userId), r -> r.str("kind"))).isEmpty();
    }
    var commentId = live.comment(postId, null, "Honestly the best kind of day.");
    assertThat(commentId).isPresent();
    assertThat(db.sql.queryOne("SELECT air_status FROM comment WHERE id = ?", List.of(commentId.get()), r -> r.str("air_status"))
        .orElseThrow()).isEqualTo("AIRED");
  }

  static int maxPerMinute(List<Timed> xs) {
    var times = new ArrayList<Long>();
    xs.forEach(x -> times.add(x.at()));
    int max = 0;
    for (long a : times) {
      int n = 0;
      for (long b : times) if (b >= a && b < a + 60_000) n++;
      max = Math.max(max, n);
    }
    return max;
  }

  static Map<String, Object> data(Timed t) {
    return t.e().data();
  }
}
