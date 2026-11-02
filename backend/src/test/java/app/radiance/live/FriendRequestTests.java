package app.radiance.live;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.graph.Onboarding;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.OfflineGateway;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SPEC §5.9: R's requests are decided within seconds, accepted or quietly left pending; residents ask too. */
class FriendRequestTests {

  static final ContentLibrary LIB = ClasspathContent.load(List.of(
      new ContentProperties.BlacklistEntry("pregnancy", List.of("pregnant"))));

  @TempDir Path dir;
  TestDatabase db;
  WorldEngine engine;
  UserActions actions;
  LivePlay live;
  String me;
  final List<LivePlay.LiveEvent> events = new ArrayList<>();

  @BeforeEach
  void world() {
    db = TestDatabase.leased(dir);
    var seed = SkeletonGenerator.generate(LIB, new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
    new SeedImporter(db.sql, db.lane, LIB).importSeed(seed, db.clock.nowMs());
    var fakes = new FakeResponses();
    FakeRender.register(fakes);
    LlmGateway gateway = OfflineGateway.create(fakes, app.radiance.kernel.WallClock.system());
    new Onboarding(db.sql, db.lane, gateway, LIB, 1.0).onboard(new Onboarding.Profile("Sam Rivera", "Engineer, runner.", "Acme",
        "Logan Square", List.of("softball"), List.of("tech", "fitness"), Onboarding.Backstory.LOCAL), 7, db.clock.nowMs());
    engine = new WorldEngine(db.sql, db.lane, LIB, Knobs.defaults());
    actions = new UserActions(db.sql, db.lane, engine);
    var backfill = new LivePlay.Backfill() {
      @Override
      public boolean running() {
        return false;
      }

      @Override
      public void start() {}
    };
    live = new LivePlay(db.sql, db.lane, engine, actions, new Appraiser(gateway, Duration.ofSeconds(5)), gateway, db.clock,
        db.holder, backfill, "Radiance", true, Duration.ofMinutes(10)).manualLoop();
    live.addListener(events::add);
    me = engine.locked(w -> w.userId);
    live.touch();
  }

  @AfterEach
  void close() {
    live.stop();
    db.close();
  }

  /** A resident who isn't R's friend yet, with this meter for him. */
  String stranger(String handle, double meter, double familiarity) {
    return engine.locked(w -> {
      var b = w.byHandle().get(handle);
      var there = w.edge(b.id, w.userId);
      var back = w.edge(w.userId, b.id);
      there.connected = false;
      back.connected = false;
      there.affinity = meter;
      there.familiarity = familiarity;
      there.dirty = true;
      back.dirty = true;
      return b.id;
    });
  }

  String status(String id) {
    return db.sql.queryOne("SELECT status FROM friend_request WHERE id = ?", List.of(id), r -> r.str("status")).orElseThrow();
  }

  boolean connected(String from, String to) {
    return engine.locked(w -> w.edge(from, to).connected);
  }

  void settle() {
    db.clock.advance(Duration.ofSeconds(20));
    live.tick();
  }

  @Test
  void someoneWhoLikesRAcceptsWithinSecondsAndTheyBecomeFriends() {
    String sarah = stranger("kristen.wagner", 40, 10);
    String id = live.requestFriend(sarah).orElseThrow();
    assertThat(status(id)).isEqualTo("PENDING");
    live.tick();
    assertThat(status(id)).as("not instantly").isEqualTo("PENDING");

    settle();

    assertThat(status(id)).isEqualTo("ACCEPTED");
    assertThat(connected(sarah, me)).isTrue();
    assertThat(connected(me, sarah)).isTrue();
    assertThat(db.sql.queryOne("SELECT connected FROM relationship WHERE from_id = ? AND to_id = ?", List.of(me, sarah),
        r -> r.lng("connected")).orElseThrow()).as("saved, so the feed and profile see it").isEqualTo(1);
    assertThat(db.sql.queryOne("SELECT COUNT(*) AS n FROM notification WHERE kind = 'FRIEND_ACCEPT' AND actor_id = ?", List.of(sarah),
        r -> r.lng("n")).orElseThrow()).isEqualTo(1);
    assertThat(events).anyMatch(e -> e.name().equals("friend.changed") && e.data().equals(Map.of("actorId", sarah, "accepted", true)));
  }

  @Test
  void someoneWhoDoesntCareForRQuietlyLeavesItPending() {
    String grouch = stranger("deepa.kulkarni", -30, 10);
    String id = live.requestFriend(grouch).orElseThrow();
    settle();
    assertThat(status(id)).isEqualTo("PENDING");
    assertThat(connected(grouch, me)).isFalse();
    assertThat(events).noneMatch(e -> e.name().equals("friend.changed"));
    assertThat(db.sql.queryOne("SELECT COUNT(*) AS n FROM notification WHERE kind = 'FRIEND_ACCEPT'", List.of(), r -> r.lng("n")).orElseThrow())
        .as("no rejection note, or anything else").isZero();
  }

  @Test
  void aSecondRequestOrOneToAFriendGoesNowhere() {
    String sarah = stranger("kristen.wagner", -30, 10);
    assertThat(live.requestFriend(sarah)).isPresent();
    assertThat(live.requestFriend(sarah)).as("already pending").isEmpty();
    String friend = stranger("marko.jovanovic", 40, 10);
    engine.locked(w -> {
      w.edge(w.userId, friend).connected = true;
      return null;
    });
    assertThat(live.requestFriend(friend)).as("already friends").isEmpty();
    assertThat(live.requestFriend(me)).as("yourself").isEmpty();
  }

  @Test
  void aResidentWhoKnowsAndLikesRAsksOnceAndRCanAccept() {
    String fan = stranger("kristen.wagner", 50, 60);
    var sent = actions.residentFriendRequests(1, db.clock.nowMs());
    assertThat(sent).hasSize(1);
    String id = sent.getFirst();
    assertThat(status(id)).isEqualTo("PENDING");
    assertThat(db.sql.queryOne("SELECT from_id FROM friend_request WHERE id = ?", List.of(id), r -> r.str("from_id")).orElseThrow()).isEqualTo(fan);
    assertThat(db.sql.queryOne("SELECT COUNT(*) AS n FROM notification WHERE kind = 'FRIEND_REQUEST'", List.of(), r -> r.lng("n")).orElseThrow()).isEqualTo(1);
    assertThat(actions.residentFriendRequests(5, db.clock.nowMs())).as("once").doesNotContain(id);

    assertThat(live.answerFriendRequest(id, true)).isTrue();
    assertThat(status(id)).isEqualTo("ACCEPTED");
    assertThat(connected(me, fan)).isTrue();
    assertThat(live.answerFriendRequest(id, false)).as("already answered").isFalse();
  }

  @Test
  void decliningLeavesThemNotFriends() {
    String fan = stranger("kristen.wagner", 50, 60);
    String id = actions.residentFriendRequests(1, db.clock.nowMs()).getFirst();
    assertThat(live.answerFriendRequest(id, false)).isTrue();
    assertThat(status(id)).isEqualTo("DECLINED");
    assertThat(connected(me, fan)).isFalse();
  }

  @Test
  void aRequestLeftPendingIsConsideredAgainNextSession() {
    String warmer = stranger("kristen.wagner", -30, 10);
    String id = live.requestFriend(warmer).orElseThrow();
    settle();
    assertThat(status(id)).isEqualTo("PENDING");
    engine.locked(w -> {
      w.edge(warmer, w.userId).affinity = 40;
      return null;
    });
    db.clock.advance(Duration.ofHours(1)); // the old session ran out
    live.tick();
    live.touch();
    settle();
    assertThat(status(id)).isEqualTo("ACCEPTED");
  }

  double meter(String resident) {
    return engine.locked(w -> w.edge(resident, w.userId).affinity);
  }

  @Test
  void decliningAResidentsRequestStingsAndAcceptingWarmsThem() {
    String fan = stranger("kristen.wagner", 50, 60);
    String id = actions.residentFriendRequests(1, db.clock.nowMs()).getFirst();
    double before = meter(fan);
    assertThat(live.answerFriendRequest(id, false)).isTrue();
    assertThat(meter(fan)).as("friend_request_ignored").isLessThan(before);

    String other = stranger("marko.jovanovic", 50, 60);
    String second = actions.residentFriendRequests(1, db.clock.nowMs()).getFirst();
    double was = meter(other);
    assertThat(live.answerFriendRequest(second, true)).isTrue();
    assertThat(meter(other)).as("friend_request_accepted").isGreaterThan(was);
  }

  @Test
  void asking_MakesThemAwareOfR() {
    String sarah = stranger("kristen.wagner", -30, 10);
    double before = engine.locked(w -> w.edge(sarah, w.userId).familiarity);
    live.requestFriend(sarah).orElseThrow();
    double after = engine.locked(w -> w.edge(sarah, w.userId).familiarity);
    assertThat(after).as("friend_request_sent").isGreaterThan(before);
  }
}
