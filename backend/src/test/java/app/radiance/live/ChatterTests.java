package app.radiance.live;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.graph.Onboarding;
import app.radiance.persistence.TestDatabase;
import app.radiance.sim.Knobs;
import app.radiance.sim.UserActions;
import app.radiance.sim.WorldEngine;
import app.radiance.social.SocialRepository;
import app.radiance.world.genesis.SeedImporter;
import app.radiance.world.genesis.SkeletonGenerator;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The session's own chatter, on the real loop's turn with offline models: the community is doing things from
 * the moment R logs in, without him causing any of it, and it stays within what he can see and who'd really do it.
 */
class ChatterTests {

  @TempDir Path dir;
  TestDatabase db;
  WorldEngine engine;
  LivePlay live;
  final List<LivePlayTests.Timed> events = new CopyOnWriteArrayList<>();
  String me;
  List<String> friends;

  @BeforeEach
  void world() {
    db = TestDatabase.leased(dir);
    var seed = SkeletonGenerator.generate(LivePlayTests.LIB, new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
    new SeedImporter(db.sql, db.lane, LivePlayTests.LIB).importSeed(seed, db.clock.nowMs());
    var gateway = LivePlayTests.gateway(null);
    new Onboarding(db.sql, db.lane, gateway, LivePlayTests.LIB, 1.0).onboard(new Onboarding.Profile("Sam Rivera", "Engineer, runner.",
        "Acme", "Logan Square", List.of("softball"), List.of("tech", "fitness"), Onboarding.Backstory.LOCAL), 7, db.clock.nowMs());
    engine = new WorldEngine(db.sql, db.lane, LivePlayTests.LIB, Knobs.defaults());
    me = engine.world().userId;
    friends = db.sql.query("SELECT to_id FROM relationship WHERE from_id = ? AND connected = 1 ORDER BY to_id", List.of(me), r -> r.str("to_id"));
    assertThat(friends).hasSizeGreaterThan(3);
    // some threads to talk on, with a little already said
    long t = db.clock.nowMs();
    var uow = new app.radiance.persistence.UnitOfWork();
    var social = new SocialRepository(db.sql);
    var brief = new app.radiance.sim.Brief(null, "DAILY", null, null, null, null, List.of("food"), 0.4, 0, java.util.Map.of());
    var posts = new java.util.ArrayList<app.radiance.social.PostRow>();
    var comments = new java.util.ArrayList<app.radiance.social.CommentRow>();
    for (int i = 0; i < 4; i++) {
      posts.add(new app.radiance.social.PostRow("thread-" + i, friends.get(i), t + 1_000 + i, app.radiance.social.PostRow.Kind.STATUS,
          app.radiance.social.PostRow.Audience.FRIENDS, app.radiance.kernel.ContentOrigin.SCRIPT, app.radiance.kernel.AirStatus.SCRIPTED,
          "Tried a new recipe tonight.", app.radiance.kernel.RenderStatus.RENDERED, brief.json(), null, null, null, null, null, 3,
          app.radiance.kernel.Fidelity.FULL, "{}", 0));
      comments.add(new app.radiance.social.CommentRow("said-" + i, "thread-" + i, null, friends.get((i + 1) % 4), t + 2_000 + i,
          app.radiance.kernel.ContentOrigin.SCRIPT, app.radiance.kernel.AirStatus.SCRIPTED, "Looks great!", null,
          app.radiance.kernel.RenderStatus.RENDERED, new app.radiance.sim.Brief(null, "DAILY", null, "SUPPORT", friends.get(i), "supportive_comment", List.of("food"), 0.4, 0,
              java.util.Map.of()).json(), null, "{}"));
    }
    social.savePosts(uow, posts);
    social.saveComments(uow, comments);
    db.commit(uow);
    db.clock.advance(Duration.ofMinutes(1));
    engine.advanceTo(db.clock.nowMs());
    var backfill = new LivePlay.Backfill() {
      @Override
      public boolean running() {
        return false;
      }

      @Override
      public void start() {}
    };
    live = new LivePlay(db.sql, db.lane, engine, new UserActions(db.sql, db.lane, engine), new Appraiser(gateway, Duration.ofSeconds(5)), gateway, db.clock,
        db.holder, backfill, "Radiance", true, Duration.ofHours(2), 1.0).manualLoop().withChatter();
    live.addListener(e -> events.add(new LivePlayTests.Timed(db.clock.nowMs(), e)));
  }

  @AfterEach
  void close() throws InterruptedException {
    live.stop();
    assertThat(live.awaitIdle(Duration.ofSeconds(30))).isTrue();
    db.close();
  }

  private void play(Duration d) throws InterruptedException {
    long end = db.clock.nowMs() + d.toMillis();
    while (db.clock.nowMs() < end) {
      db.clock.advance(Duration.ofSeconds(2));
      live.tick();
      assertThat(live.awaitIdle(Duration.ofSeconds(20))).isTrue();
    }
  }

  private long live(String table) {
    return db.sql.queryOne("SELECT COUNT(*) AS n FROM " + table + " WHERE origin = 'LIVE' AND air_status = 'AIRED' AND render_status = 'RENDERED'", List.of(), r -> r.lng("n")).orElse(0L);
  }

  /** R's direction: "active chatter going at a reasonable rate when I log in immediately". */
  @Test
  void theCommunityIsGoingFromTheMomentRLogsIn_WithoutHimDoingAnything() throws Exception {
    live.touch();
    long start = db.clock.nowMs();
    play(Duration.ofSeconds(45));
    assertThat(events).as("something within the first 45 seconds").anyMatch(e -> e.e().name().equals("comment.aired") || e.e().name().equals("reaction.changed"));
    play(Duration.ofMinutes(30));
    assertThat(live("comment")).as("comments in 30 minutes").isBetween(8L, 70L);
    assertThat(db.sql.queryOne("SELECT COUNT(*) AS n FROM reaction WHERE created_at > ? AND air_status = 'AIRED' AND actor_id <> ?", List.of(start, me), r -> r.lng("n")).orElse(0L))
        .as("reactions in 30 minutes").isGreaterThan(10);
    assertThat(db.sql.queryOne("SELECT COUNT(*) AS n FROM comment WHERE origin = 'LIVE' AND parent_id IS NOT NULL AND author_id <> ? AND air_status = 'AIRED'", List.of(me), r -> r.lng("n")).orElse(0L))
        .as("residents answering each other, not only aimed at R").isPositive();
  }

  @Test
  void typingDotsShowBeforeACommentLands_AndGoAway() throws Exception {
    live.touch();
    play(Duration.ofMinutes(8));
    var names = events.stream().map(e -> e.e().name()).toList();
    assertThat(names).contains("typing.started", "typing.stopped");
    long started = events.stream().filter(e -> e.e().name().equals("typing.started")).findFirst().orElseThrow().at();
    long aired = events.stream().filter(e -> e.e().name().equals("comment.aired")).filter(e -> e.at() >= started).findFirst().orElseThrow().at();
    assertThat(aired).isGreaterThan(started);
    assertThat(names.stream().filter("typing.started"::equals).count()).isGreaterThanOrEqualTo(names.stream().filter("typing.stopped"::equals).count());
  }

  @Test
  void everythingHappensWhereRCanSeeIt_AndNobodySpeaksForHim() throws Exception {
    live.touch();
    play(Duration.ofMinutes(20));
    assertThat(db.sql.query("SELECT author_id FROM comment WHERE origin = 'LIVE'", List.of(), r -> r.str("author_id"))).doesNotContain(me);
    var visible = new HashSet<>(friends);
    visible.add(me);
    assertThat(db.sql.query("SELECT p.author_id, p.headline_id FROM comment c JOIN post p ON p.id = c.post_id WHERE c.origin = 'LIVE'", List.of(),
        r -> r.isNull("headline_id") ? r.str("author_id") : me)).allMatch(visible::contains);
    // a thread burns out instead of taking the whole session
    assertThat(db.sql.query("SELECT COUNT(*) AS n FROM comment WHERE origin = 'LIVE' GROUP BY post_id", List.of(), r -> r.lng("n")))
        .allMatch(n -> n <= 8);
    // nobody talks over themselves: no one has a lopsided share of what was said
    var byAuthor = db.sql.query("SELECT author_id, COUNT(*) AS n FROM comment WHERE origin = 'LIVE' GROUP BY author_id", List.of(), r -> r.lng("n"));
    long total = byAuthor.stream().mapToLong(Long::longValue).sum();
    if (total >= 8) assertThat(byAuthor.stream().mapToLong(Long::longValue).max().orElse(0)).isLessThan(total * 2 / 3 + 1);
  }

  /** "A post edited or deleted": someone thinks better of it, and it goes away everywhere. */
  @Test
  void aResidentTakingACommentBackRemovesItEverywhere() throws Exception {
    var actions = new UserActions(db.sql, db.lane, engine);
    assertThat(actions.takeBack("said-0")).contains("thread-0");
    assertThat(db.sql.queryOne("SELECT air_status FROM comment WHERE id = 'said-0'", List.of(), r -> r.str("air_status")).orElseThrow()).isEqualTo("DROPPED");
    assertThat(db.sql.queryOne("SELECT comment_count FROM post WHERE id = 'thread-0'", List.of(), r -> r.lng("comment_count")).orElseThrow()).isZero();
    assertThat(engine.item("thread-0").commenters).doesNotContain(friends.get(1));
    assertThat(actions.takeBack("no-such-comment")).isEmpty();
  }

  @Test
  void itIsOffUnlessAskedFor_SoPacerTestsOnlySeeWhatRCauses() throws Exception {
    var quiet = new LivePlay(db.sql, db.lane, engine, new UserActions(db.sql, db.lane, engine), new Appraiser(LivePlayTests.gateway(null), Duration.ofSeconds(5)),
        LivePlayTests.gateway(null), db.clock, db.holder, new LivePlay.Backfill() {
          @Override
          public boolean running() {
            return false;
          }

          @Override
          public void start() {}
        }, "Radiance", true, Duration.ofHours(2), 1.0).manualLoop();
    quiet.touch();
    for (int i = 0; i < 60; i++) {
      db.clock.advance(Duration.ofSeconds(2));
      quiet.tick();
    }
    assertThat(live("comment")).isZero();
    quiet.stop();
    assertThat(quiet.awaitIdle(Duration.ofSeconds(10))).isTrue();
  }
}
