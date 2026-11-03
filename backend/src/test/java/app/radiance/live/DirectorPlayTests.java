package app.radiance.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.graph.Onboarding;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.OfflineGateway;
import app.radiance.narrative.FakeRender;
import app.radiance.persistence.TestDatabase;
import app.radiance.sim.Incidents;
import app.radiance.sim.Knobs;
import app.radiance.sim.ScriptWriter;
import app.radiance.sim.UserActions;
import app.radiance.sim.WorldEngine;
import app.radiance.sim.ContinuityAuditor;
import app.radiance.world.genesis.SeedImporter;
import app.radiance.world.genesis.SkeletonGenerator;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SPEC §12 M13 AC, on the real engine and live play: inject, fast-forward, blacklist, pause. */
class DirectorPlayTests {

  static final long HOUR = 3_600_000L;

  @TempDir Path dir;
  TestDatabase db;
  ContentLibrary lib;
  WorldEngine engine;
  LivePlay live;
  Incidents incidents;
  final AtomicInteger backfills = new AtomicInteger();

  @BeforeEach
  void world() {
    lib = ClasspathContent.load(List.of(new ContentProperties.BlacklistEntry("pregnancy", List.of("pregnant"))));
    db = TestDatabase.leased(dir.resolve("db"));
    var seed = SkeletonGenerator.generate(lib, new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
    new SeedImporter(db.sql, db.lane, lib).importSeed(seed, db.clock.nowMs());
    var fakes = new FakeResponses();
    FakeRender.register(fakes);
    Appraiser.registerFake(fakes);
    LlmGateway gateway = OfflineGateway.create(fakes, app.radiance.kernel.WallClock.system());
    new Onboarding(db.sql, db.lane, gateway, lib, 1.0).onboard(new Onboarding.Profile("Sam Rivera", "Engineer, runner.", "Acme",
        "Logan Square", List.of("softball"), List.of("tech", "fitness"), Onboarding.Backstory.LOCAL), 7, db.clock.nowMs());
    engine = new WorldEngine(db.sql, db.lane, lib, Knobs.defaults());
    var backfill = new LivePlay.Backfill() {
      @Override
      public boolean running() {
        return false;
      }

      @Override
      public void start() {
        backfills.incrementAndGet();
      }
    };
    live = new LivePlay(db.sql, db.lane, engine, new UserActions(db.sql, db.lane, engine), new Appraiser(gateway, Duration.ofSeconds(5)), gateway,
        db.clock, db.holder, backfill, "Radiance", true, Duration.ofMinutes(10)).manualLoop();
    incidents = new Incidents(engine);
    db.clock.advance(Duration.ofMinutes(5));
    live.touch();
  }

  @AfterEach
  void close() throws Exception {
    live.stop();
    assertThat(live.awaitIdle(Duration.ofSeconds(20))).isTrue();
    db.close();
  }

  long count(String sql, Object... args) {
    return db.sql.queryOne(sql, List.of(args), r -> r.lng("n")).orElseThrow();
  }

  String one(String sql, Object... args) {
    return db.sql.queryOne(sql, List.of(args), r -> r.str("v")).orElse(null);
  }

  long committed() {
    return engine.locked(w -> w.time);
  }

  Incidents.Couple couple() {
    var couples = incidents.couples();
    assertThat(couples).as("this world has a couple to split").isNotEmpty();
    return couples.getFirst();
  }

  @Test
  void aBreakupInjected_ThenSixHoursOn_ItsFalloutHasAiredInTheFeed() throws Exception {
    var pair = couple();
    var script = incidents.breakup(pair.a(), pair.b(), live.now());
    assertThat(script.posts).as("someone says something").isNotEmpty();
    assertThat(script.reactions).as("friends notice").isNotEmpty();
    assertThat(script.events).allMatch(e -> e.source() == app.radiance.story.EventRow.Source.DIRECTOR);
    String post = script.posts.getFirst().id();
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE id = ?", post)).as("nothing is there yet").isZero();

    live.injected(script);
    assertThat(live.awaitIdle(Duration.ofSeconds(10))).isTrue();
    assertThat(one("SELECT air_status AS v FROM post WHERE id = ?", post)).as("no spoilers: it hasn't aired").isEqualTo("SCRIPTED");

    live.fastForward(6);
    assertThat(live.awaitIdle(Duration.ofSeconds(20))).isTrue();

    assertThat(one("SELECT air_status AS v FROM post WHERE id = ?", post)).isEqualTo("AIRED");
    assertThat(one("SELECT render_status AS v FROM post WHERE id = ?", post)).as("it's written").isEqualTo("RENDERED");
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE id = ? AND air_status = 'AIRED' AND render_status = 'RENDERED' AND body IS NOT NULL", post)).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) AS n FROM reaction WHERE target_id = ? AND air_status = 'AIRED'", post)).as("the fallout").isPositive();
    assertThat(one("SELECT kind AS v FROM relationship WHERE from_id = ? AND to_id = ?", pair.a(), pair.b())).isEqualTo("EX");
    String partner = engine.locked(w -> w.person(pair.a()).partnerId);
    assertThat(partner).as("the world changed, not just the feed").isNull();
    assertThat(incidents.couples()).as("they aren't a couple any more").doesNotContain(pair);
    assertThatThrownBy(() -> incidents.breakup(pair.a(), pair.b(), live.now())).isInstanceOf(Incidents.Refused.class);
  }

  @Test
  void onlyACoupleCanBreakUp() {
    var pair = couple();
    String stranger = engine.locked(w -> w.residents().stream().filter(p -> !p.id.equals(pair.a()) && !p.id.equals(pair.b())).findFirst().orElseThrow().id);
    assertThatThrownBy(() -> incidents.breakup(pair.a(), stranger, live.now())).isInstanceOf(Incidents.Refused.class);
    assertThatThrownBy(() -> incidents.breakup(pair.a(), pair.a(), live.now())).isInstanceOf(Incidents.Refused.class);
    assertThatThrownBy(() -> incidents.breakup(pair.a(), "nobody", live.now())).isInstanceOf(Incidents.Refused.class);
    String user = engine.locked(w -> w.userId);
    assertThatThrownBy(() -> incidents.breakup(pair.a(), user, live.now())).as("not R: he's playing").isInstanceOf(Incidents.Refused.class);
  }

  @Test
  void romanceIsOnlyEverBetweenAManAndAWoman() {
    String[] two = engine.locked(w -> {
      var first = w.residents().getFirst();
      var same = w.residents().stream().filter(p -> p.gender == first.gender && !p.id.equals(first.id)).findFirst().orElseThrow();
      return new String[] {first.id, same.id};
    });
    assertThatThrownBy(() -> incidents.arc("romance", two[0], two[1], live.now())).isInstanceOf(Incidents.Refused.class).hasMessageContaining("a man and a woman");
    assertThatThrownBy(() -> incidents.arc("romance", two[0], null, live.now())).isInstanceOf(Incidents.Refused.class);
  }

  @Test
  void anyEventCanBeFired_AndNonsenseIsRefused() throws Exception {
    String who = engine.locked(w -> w.residents().getFirst().id);
    var def = lib.events().values().stream().filter(e -> e.participants().isEmpty() && e.output() != null).findFirst().orElseThrow();
    var script = incidents.event(def.id(), who, null, live.now());
    assertThat(script.events).singleElement().satisfies(e -> {
      assertThat(e.defId()).isEqualTo(def.id());
      assertThat(e.source()).isEqualTo(app.radiance.story.EventRow.Source.DIRECTOR);
    });
    live.injected(script);
    live.awaitIdle(Duration.ofSeconds(10));
    live.fastForward(1);
    live.awaitIdle(Duration.ofSeconds(10));
    assertThat(one("SELECT air_status AS v FROM event WHERE id = ?", script.events.getFirst().id())).isEqualTo("AIRED");
    assertThatThrownBy(() -> incidents.event("no_such_event", who, null, live.now())).isInstanceOf(Incidents.Refused.class);
    assertThatThrownBy(() -> incidents.event(def.id(), "nobody", null, live.now())).isInstanceOf(Incidents.Refused.class);
    var needsTwo = lib.events().values().stream().filter(e -> !e.participants().isEmpty()).findFirst().orElseThrow();
    assertThatThrownBy(() -> incidents.event(needsTwo.id(), who, null, live.now())).isInstanceOf(Incidents.Refused.class).hasMessageContaining("someone else");
  }

  /** AC: adding an arc to the blacklist stops new instances immediately, even ones already on the script. */
  @Test
  void blacklistingAnArcStopsNewInstancesImmediately() throws Exception {
    String who = engine.locked(w -> w.residents().getFirst().id);
    var onTheScript = incidents.arc("new_hobby", who, null, live.now());
    live.injected(onTheScript);
    live.awaitIdle(Duration.ofSeconds(10));

    lib.block("new_hobby");

    assertThat(lib.startableArcs()).doesNotContainKey("new_hobby");
    assertThatThrownBy(() -> incidents.arc("new_hobby", who, null, live.now())).isInstanceOf(Incidents.Refused.class).hasMessageContaining("blacklisted");
    live.fastForward(1);
    live.awaitIdle(Duration.ofSeconds(10));
    String start = onTheScript.events.getFirst().id();
    assertThat(one("SELECT air_status AS v FROM event WHERE id = ?", start)).as("the start was already scripted, and airing refuses it").isEqualTo("DROPPED");
    assertThat(count("SELECT COUNT(*) AS n FROM arc WHERE id = ?", onTheScript.events.getFirst().arcId())).isZero();
    assertThat(engine.recentDrops()).as("the airing report says why").anyMatch(d -> d.reason().contains("blacklisted"));

    long before = count("SELECT COUNT(*) AS n FROM event WHERE def_id = 'new_hobby'");
    long t = engine.locked(w -> w.time);
    new ScriptWriter(db.sql, db.lane, engine, ContinuityAuditor.Mode.RECORD, db.clock).write(t, t + 14 * 24 * HOUR, Fidelity.FULL, List.of(), engine.weather(t, t + 14 * 24 * HOUR), Set.of(), ContentOrigin.SCRIPT);
    assertThat(count("SELECT COUNT(*) AS n FROM event WHERE def_id = 'new_hobby'")).as("two weeks of planning start none").isEqualTo(before);

    lib.unblock("new_hobby");
    assertThat(lib.startableArcs()).containsKey("new_hobby");
  }

  @Test
  void pausingStopsTheClock_AndResumingPicksUpWhereItStopped() throws Exception {
    live.pause();
    long held = live.now();
    db.clock.advance(Duration.ofHours(2));
    assertThat(live.now()).as("the clock stands still").isEqualTo(held);
    assertThat(live.paused()).isTrue();
    long committed = committed();
    live.tick();
    assertThat(committed()).as("nothing airs").isEqualTo(committed);

    live.resume();
    assertThat(live.awaitIdle(Duration.ofSeconds(20))).isTrue();
    assertThat(live.paused()).isFalse();
    assertThat(live.now()).as("no time passed in the world").isBetween(held, held + 1_000);
    db.clock.advance(Duration.ofMinutes(1));
    assertThat(live.now()).isGreaterThanOrEqualTo(held + 60_000);
  }

  @Test
  void fastForwardingWhilePausedMovesTheHeldTime_AndStaysPaused() throws Exception {
    live.pause();
    backfills.set(0);
    long held = live.now();
    live.fastForward(3);
    assertThat(live.awaitIdle(Duration.ofSeconds(20))).isTrue();
    assertThat(live.now()).isEqualTo(held + 3 * HOUR);
    assertThat(live.paused()).isTrue();
    assertThat(committed()).as("what was due has aired").isEqualTo(held + 3 * HOUR);
    assertThat(backfills).as("no backfill for a stopped world").hasValue(0);
  }

  @Test
  void fastForwardingPastTheScriptStartsABackfill() throws Exception {
    long scripted = db.sql.queryOne("SELECT scripted_until AS n FROM world", List.of(), r -> r.lng("n")).orElseThrow();
    long hours = Math.max(0, (scripted - live.now()) / HOUR) + 2;
    backfills.set(0);
    live.fastForward(hours);
    assertThat(live.awaitIdle(Duration.ofSeconds(30))).isTrue();
    assertThat(backfills.get()).isPositive();
  }

  @Test
  void anUntouchedWorldReadsTheSameTimeAsBefore() {
    long offset = db.sql.queryOne("SELECT time_offset_ms AS n FROM world", List.of(), r -> r.lng("n")).orElseThrow();
    assertThat(live.now()).isEqualTo(db.clock.nowMs() + offset);
  }
}
