package app.radiance.live;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.graph.Onboarding;
import app.radiance.kernel.Json;
import app.radiance.llm.LlmGateway;
import app.radiance.messenger.DmRepository;
import app.radiance.narrative.ContextPacketBuilder;
import app.radiance.narrative.Maintenance;
import app.radiance.persistence.TestDatabase;
import app.radiance.persistence.UnitOfWork;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Messenger (SPEC §5.11, §12 M8 AC) on the seed-7 world, offline models: replies in seconds with typing
 * first, the cold shoulder by like-meter, the crush who checks in early, and thread summaries.
 */
class MessengerTests {

  @TempDir Path dir;
  TestDatabase db;
  WorldEngine engine;
  UserActions actions;
  final List<LivePlayTests.Timed> events = new CopyOnWriteArrayList<>();

  @BeforeEach
  void world() {
    db = TestDatabase.leased(dir);
    var seed = SkeletonGenerator.generate(LivePlayTests.LIB,
        new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
    new SeedImporter(db.sql, db.lane, LivePlayTests.LIB).importSeed(seed, db.clock.nowMs());
    new Onboarding(db.sql, db.lane, LivePlayTests.gateway(null), LivePlayTests.LIB, 1.0).onboard(new Onboarding.Profile(
        "Sam Rivera", "Engineer, runner.", "Acme", "Logan Square", List.of("softball"), List.of("tech", "fitness"),
        Onboarding.Backstory.LOCAL), 7, db.clock.nowMs());
    engine = new WorldEngine(db.sql, db.lane, LivePlayTests.LIB, Knobs.defaults());
    actions = new UserActions(db.sql, db.lane, engine);
    db.clock.advance(Duration.ofMinutes(5));
  }

  @AfterEach
  void close() {
    db.close();
  }

  LivePlay live(LlmGateway gateway, double coldShoulder) {
    var backfill = new LivePlay.Backfill() {
      @Override
      public boolean running() {
        return false;
      }

      @Override
      public void start() {}
    };
    var live = new LivePlay(db.sql, db.lane, engine, actions, new Appraiser(gateway, Duration.ofSeconds(5)), gateway, db.clock,
        db.holder, backfill, "Radiance", true, Duration.ofMinutes(10), coldShoulder).manualLoop();
    live.addListener(e -> events.add(new LivePlayTests.Timed(db.clock.nowMs(), e)));
    return live;
  }

  void play(LivePlay live, Duration d, long step) throws InterruptedException {
    long end = db.clock.nowMs() + d.toMillis();
    long beat = db.clock.nowMs();
    while (db.clock.nowMs() < end) {
      db.clock.advance(Duration.ofMillis(step));
      if (db.clock.nowMs() - beat >= 30_000) {
        live.heartbeat();
        beat = db.clock.nowMs();
      }
      live.tick();
      Thread.sleep(2);
    }
  }

  List<LivePlayTests.Timed> named(String name, String threadId, long since) {
    return events.stream().filter(t -> t.e().name().equals(name) && t.at() >= since
        && threadId.equals(t.e().data().get("threadId"))).toList();
  }

  void setAffinity(String resident, double affinity) {
    engine.locked(w -> w.edge(resident, w.userId).affinity = affinity);
  }

  List<String> residents() {
    return engine.locked(w -> w.residents().stream().map(p -> p.id).sorted().toList());
  }

  // -------------------------------------------------------------------------------------- AC tests

  @Test
  void aDmToAnyResidentShowsTypingWithin3sAndAReplyWithin10s() throws Exception {
    var live = live(LivePlayTests.gateway(null), 1.0);
    live.touch();
    for (String resident : residents()) {
      setAffinity(resident, 20); // nobody hostile: everyone answers
      String thread = live.openThread(resident);
      long t0 = db.clock.nowMs();
      live.dm(thread, "hey! are you going to the softball thing saturday?");
      play(live, Duration.ofSeconds(12), 250);
      var typing = named("typing.started", thread, t0);
      var replies = named("dm.message", thread, t0).stream().filter(t -> resident.equals(t.e().data().get("senderId"))).toList();
      assertThat(typing).as("typing dots from " + resident).isNotEmpty();
      assertThat(typing.getFirst().at() - t0).as("typing starts within 3 s").isLessThanOrEqualTo(3_000);
      assertThat(replies).as("a reply from " + resident).isNotEmpty();
      assertThat(replies.getFirst().at() - t0).as("the reply lands within about 10 s").isLessThanOrEqualTo(10_000);
      assertThat(named("dm.seen", thread, t0)).as("Seen when they start typing").isNotEmpty();
    }
  }

  @Test
  void dislikeMeansCurtAndOnlyTheHostileLeaveRonSeen() {
    var someone = residents().get(3);
    String thread = actions.thread(someone);
    int curt = 0, seen = 0, hostileSeen = 0;
    for (int i = 0; i < 40; i++) {
      long t = db.clock.nowMs() + i * 60_000L;
      for (double affinity : new double[] {40, -30, -60}) {
        setAffinity(someone, affinity);
        var msg = actions.dm(thread, "you around? " + i + affinity, Appraiser.reading(Appraiser.heuristic("you around?", true)), t)
            .orElseThrow();
        var reply = actions.dmReply(thread, msg, 1.0, t);
        if (affinity == 40) {
          assertThat(reply.leftOnSeen()).as("only the hostile leave R on Seen").isFalse();
          assertThat(reply.tone()).as("someone who likes R isn't curt").isEqualTo("normal");
        } else if (affinity == -30) {
          assertThat(reply.leftOnSeen()).as("−30 isn't hostile enough for Seen").isFalse();
          if (reply.tone().equals("curt")) curt++;
          for (var m : reply.script().dms) {
            var brief = Json.tree(m.briefJson());
            if (reply.tone().equals("curt")) {
              assertThat(brief.path("interaction").asString()).isEqualTo("dm_curt");
              assertThat(brief.path("extra").path("tone").asString()).isEqualTo("curt");
            }
          }
          assertThat(reply.script().dms.size()).isGreaterThan(0);
        } else if (reply.leftOnSeen()) {
          hostileSeen++;
          assertThat(reply.script().dms).isEmpty();
        }
        if (reply.leftOnSeen()) seen++;
      }
    }
    assertThat(curt).as("a resident who dislikes R mostly answers curtly").isGreaterThan(25);
    assertThat(hostileSeen).as("a hostile one sometimes leaves him on Seen").isPositive();
    assertThat(seen).isEqualTo(hostileSeen);

    // the knob turns it off
    setAffinity(someone, -60);
    long t = db.clock.nowMs() + 3_600_000L;
    var msg = actions.dm(thread, "hello?", Appraiser.reading(Appraiser.heuristic("hello?", true)), t).orElseThrow();
    var reply = actions.dmReply(thread, msg, 0.0, t);
    assertThat(reply.leftOnSeen()).isFalse();
    assertThat(reply.tone()).isEqualTo("normal");
  }

  @Test
  void leftOnSeenShowsRightAway() throws Exception {
    var live = live(LivePlayTests.gateway(null), 1_000.0);
    live.touch();
    var someone = residents().get(5);
    setAffinity(someone, -80);
    String thread = live.openThread(someone);
    long t0 = db.clock.nowMs();
    live.dm(thread, "so are we good or what");
    play(live, Duration.ofSeconds(15), 250);
    assertThat(named("dm.seen", thread, t0)).isNotEmpty();
    assertThat(named("dm.seen", thread, t0).getFirst().at() - t0).isLessThanOrEqualTo(1_000);
    assertThat(named("dm.message", thread, t0).stream().filter(t -> someone.equals(t.e().data().get("senderId"))))
        .as("no reply").isEmpty();
    assertThat(db.sql.queryOne("SELECT read_at FROM dm_message WHERE thread_id = ? AND sender_id <> ?", List.of(thread, someone),
        r -> r.lngOrNull("read_at")).orElse(null)).as("R's message shows Seen").isNotNull();
  }

  @Test
  void aCrushMessagesRWithinTheFirst30MinutesOfASession() throws Exception {
    var live = live(LivePlayTests.gateway(null), 1.0);
    String crush = residents().get(7);
    engine.locked(w -> w.edge(crush, w.userId).tags.add("crush"));
    long t0 = db.clock.nowMs();
    live.touch();
    play(live, Duration.ofMinutes(30), 1_000);
    var fromCrush = events.stream().filter(t -> t.e().name().equals("dm.message") && crush.equals(t.e().data().get("senderId")))
        .toList();
    assertThat(fromCrush).as("the crush checked in").isNotEmpty();
    assertThat(fromCrush.getFirst().at() - t0).isLessThanOrEqualTo(30 * 60_000L);
    var brief = db.sql.queryOne("SELECT brief_json FROM dm_message WHERE id = ?", List.of(fromCrush.getFirst().e().data().get("id")),
        r -> r.str("brief_json")).orElseThrow();
    assertThat(Json.tree(brief).path("defId").asString()).isEqualTo("dm_user_crush_checkin");
    String thread = (String) fromCrush.getFirst().e().data().get("threadId");
    var typing = named("typing.started", thread, t0);
    assertThat(typing).as("typing dots before it lands").isNotEmpty();
    assertThat(typing.getFirst().at()).isLessThanOrEqualTo(fromCrush.getFirst().at());
  }

  // ------------------------------------------------------------------------------------- real path

  /** The real loop thread on the wall clock answers a DM, not just hand-driven turns. */
  @Test
  void theRealLoopAnswersADmOnTheWallClock() throws Exception {
    var gateway = LivePlayTests.gateway(null);
    var backfill = new LivePlay.Backfill() {
      @Override
      public boolean running() {
        return false;
      }

      @Override
      public void start() {}
    };
    var live = new LivePlay(db.sql, db.lane, engine, actions, new Appraiser(gateway, Duration.ofSeconds(5)), gateway,
        app.radiance.kernel.WallClock.system(), db.holder, backfill, "Radiance", true, Duration.ofMinutes(10), 1.0);
    var got = new LinkedBlockingQueue<LivePlay.LiveEvent>();
    live.addListener(e -> {
      if (e.name().equals("dm.message")) got.add(e);
    });
    try {
      live.touch();
      String someone = residents().get(2);
      setAffinity(someone, 30);
      String thread = live.openThread(someone);
      long t0 = System.currentTimeMillis();
      live.dm(thread, "quick q, what's the best pizza near you?");
      LivePlay.LiveEvent reply = null;
      while (reply == null && System.currentTimeMillis() - t0 < 15_000) {
        var e = got.poll(500, TimeUnit.MILLISECONDS);
        if (e != null && someone.equals(e.data().get("senderId"))) reply = e;
      }
      assertThat(reply).as("a reply over the real loop").isNotNull();
      assertThat(System.currentTimeMillis() - t0).isLessThanOrEqualTo(12_000);
    } finally {
      live.stop();
    }
  }

  // ------------------------------------------------------------------------------------ regressions

  /**
   * Regression: the nightly script upserted every thread it planned a DM in with a null summary, so a
   * thread lost its summary the next time anyone messaged in it. Runs the real script writer.
   */
  @Test
  void planningMoreDmsKeepsTheThreadsSummaries() {
    var zone = engine.world().zone;
    long t0 = db.clock.nowMs();
    long day = 86_400_000L;
    var weather = new app.radiance.weather.WeatherBook(zone, LivePlayTests.LIB.weather())
        .putAll(new app.radiance.weather.FakeWeather(7, zone).hours(t0 - t0 % 3_600_000L - 8 * day, t0 + 12 * day));
    var writer = new app.radiance.sim.ScriptWriter(db.sql, db.lane, engine, app.radiance.sim.ContinuityAuditor.Mode.THROW);
    writer.write(t0, t0 + 4 * day, app.radiance.kernel.Fidelity.FULL, List.of(), weather, java.util.Set.of(),
        app.radiance.kernel.ContentOrigin.SCRIPT);
    long threads = db.sql.queryOne("SELECT COUNT(*) AS n FROM dm_thread", List.of(), r -> r.lng("n")).orElseThrow();
    assertThat(threads).as("the script planned DMs").isPositive();
    db.commit(new UnitOfWork().add("UPDATE dm_thread SET summary = 'they go way back'"));
    long before = db.sql.queryOne("SELECT COUNT(*) AS n FROM dm_message", List.of(), r -> r.lng("n")).orElseThrow();

    writer.write(t0 + 4 * day, t0 + 10 * day, app.radiance.kernel.Fidelity.FULL, List.of(), weather, java.util.Set.of(),
        app.radiance.kernel.ContentOrigin.SCRIPT);
    long reused = db.sql.queryOne("SELECT COUNT(DISTINCT m.thread_id) AS n FROM dm_message m JOIN dm_thread t ON t.id = m.thread_id"
        + " WHERE m.created_at >= ? AND t.summary IS NOT NULL", List.of(t0 + 4 * day), r -> r.lng("n")).orElseThrow();
    long lost = db.sql.queryOne("SELECT COUNT(*) AS n FROM dm_thread WHERE summary IS NULL AND id IN"
        + " (SELECT thread_id FROM dm_message WHERE created_at < ?)", List.of(t0 + 4 * day), r -> r.lng("n")).orElseThrow();
    assertThat(db.sql.queryOne("SELECT COUNT(*) AS n FROM dm_message", List.of(), r -> r.lng("n")).orElseThrow())
        .isGreaterThan(before);
    assertThat(reused + lost).as("some old thread got new DMs").isPositive();
    assertThat(lost).as("threads that lost their summary").isZero();

    // and live play's own threads
    var someone = residents().get(1);
    String thread = actions.thread(someone);
    db.commit(new UnitOfWork().add("UPDATE dm_thread SET summary = ? WHERE id = ?", "they planned a hike", thread));
    actions.save(actions.dmFrom(someone, "dm_user_advice", db.clock.nowMs()));
    assertThat(new DmRepository(db.sql).thread(thread).orElseThrow().summary()).isEqualTo("they planned a hike");
  }

  // -------------------------------------------------------------------------------- thread summaries

  @Test
  void longThreadsGetTheirOlderMessagesSummarized() {
    var someone = residents().get(4);
    String thread = actions.thread(someone);
    String me = engine.world().userId;
    var uow = new UnitOfWork();
    long t = db.clock.nowMs() - 3_600_000L;
    for (int i = 0; i < 75; i++) {
      uow.add("INSERT INTO dm_message (id, thread_id, sender_id, created_at, air_status, body, render_status, brief_json)"
          + " VALUES (?, ?, ?, ?, 'AIRED', ?, 'RENDERED', '{}')", "m" + (1000 + i), thread, i % 2 == 0 ? me : someone, t + i,
          "message number " + i);
    }
    db.commit(uow);
    var gateway = LivePlayTests.gateway(null);
    var maintenance = new Maintenance(db.sql, db.lane, LivePlayTests.LIB, gateway, engine.world(), "Radiance");
    maintenance.enqueue(db.clock.nowMs());
    assertThat(db.sql.queryOne("SELECT COUNT(*) AS n FROM job WHERE kind = 'DM_SUMMARY' AND ref_id = ?", List.of(thread),
        r -> r.lng("n")).orElseThrow()).isEqualTo(1);
    maintenance.summarizeThread(thread);
    assertThat(new DmRepository(db.sql).thread(thread).orElseThrow().summary()).isNotBlank();
    assertThat(db.sql.queryOne("SELECT summarized_count FROM dm_thread WHERE id = ?", List.of(thread), r -> r.lng("summarized_count"))
        .orElseThrow()).isEqualTo(45);

    // the packet: the summary, then only the last 30 messages
    var packet = new ContextPacketBuilder(db.sql, LivePlayTests.LIB, "Radiance", engine.world())
        .dms(thread, gateway.budgetFor(app.radiance.llm.Purpose.RENDER_DM));
    assertThat(packet.content()).contains("(earlier: ");
    assertThat(packet.content()).contains("message number 74").contains("message number 45").doesNotContain("message number 44:");
  }

  @Test
  void summariesComeDueEveryThirtyMessagesPastSixty() {
    assertThat(Maintenance.dmSummaryDue(60, 0)).isFalse();
    assertThat(Maintenance.dmSummaryDue(61, 0)).isTrue();
    assertThat(Maintenance.dmSummaryDue(80, 45)).isFalse();
    assertThat(Maintenance.dmSummaryDue(105, 45)).isTrue();
    var counts = new ArrayList<Long>();
    long done = 0;
    for (long n = 1; n <= 200; n++) {
      if (Maintenance.dmSummaryDue(n, done)) {
        counts.add(n);
        done = n - 30;
      }
    }
    assertThat(counts).containsExactly(61L, 91L, 121L, 151L, 181L);
  }
}
