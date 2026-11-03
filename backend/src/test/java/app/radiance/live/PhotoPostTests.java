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
import app.radiance.llm.Part;
import app.radiance.llm.PromptBudget;
import app.radiance.llm.Purpose;
import app.radiance.media.LocalFileMediaStore;
import app.radiance.media.MediaRow;
import app.radiance.media.Uploads;
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
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SPEC §12 M12 AC: R uploads a photo of a dog and residents react to the dog within the session. */
class PhotoPostTests {

  static final ContentLibrary LIB = ClasspathContent.load(List.of(
      new ContentProperties.BlacklistEntry("pregnancy", List.of("pregnant"))));
  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
  static final Appraiser.PhotoAppraisal DOG =
      new Appraiser.PhotoAppraisal("a golden retriever puppy asleep on a couch", List.of("pets"), 0.9, 0.3, 0, 0.1, 0.9, 0, 0);

  @TempDir Path dir;
  TestDatabase db;
  WorldEngine engine;
  LivePlay live;
  Uploads uploads;
  final List<String> rendered = new CopyOnWriteArrayList<>();
  final List<List<Part>> looked = new CopyOnWriteArrayList<>();
  volatile boolean visionFails;

  @BeforeEach
  void world() {
    db = TestDatabase.leased(dir.resolve("db"));
    var seed = SkeletonGenerator.generate(LIB, new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
    new SeedImporter(db.sql, db.lane, LIB).importSeed(seed, db.clock.nowMs());
    var fakes = new FakeResponses();
    FakeRender.register(fakes);
    Appraiser.registerFake(fakes);
    fakes.register(Appraiser.PhotoAppraisal.class, call -> {
      if (visionFails) throw new IllegalStateException("vision is down");
      return DOG;
    });
    LlmGateway offline = OfflineGateway.create(fakes, app.radiance.kernel.WallClock.system());
    LlmGateway gateway = new LlmGateway() {
      @Override
      public <T> LlmResult<T> call(LlmRequest<T> r) {
        if (r.purpose() == Purpose.VISION_APPRAISAL) looked.add(r.content());
        if (r.purpose() == Purpose.RENDER_THREAD) {
          for (var p : r.content()) if (p instanceof Part.Text t) rendered.add(t.text());
        }
        return offline.call(r);
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
    new Onboarding(db.sql, db.lane, gateway, LIB, 1.0).onboard(new Onboarding.Profile("Sam Rivera", "Engineer, runner.", "Acme",
        "Logan Square", List.of("softball"), List.of("tech", "fitness"), Onboarding.Backstory.LOCAL), 7, db.clock.nowMs());
    engine = new WorldEngine(db.sql, db.lane, LIB, Knobs.defaults());
    var backfill = new LivePlay.Backfill() {
      @Override
      public boolean running() {
        return false;
      }

      @Override
      public void start() {}
    };
    live = new LivePlay(db.sql, db.lane, engine, new UserActions(db.sql, db.lane, engine), new Appraiser(gateway, Duration.ofSeconds(5)), gateway,
        db.clock, db.holder, backfill, "Radiance", true, Duration.ofMinutes(10)).manualLoop();
    uploads = new Uploads(db.sql, db.lane, new LocalFileMediaStore(dir.resolve("media")), db.clock);
    db.clock.advance(Duration.ofMinutes(5));
    live.touch();
  }

  @AfterEach
  void close() throws Exception {
    live.stop();
    assertThat(live.awaitIdle(Duration.ofSeconds(20))).isTrue();
    db.close();
  }

  MediaRow uploaded() throws Exception {
    var row = uploads.start("image/jpeg", JPEG.length, 1600, 1200);
    uploads.receive(row.id(), false, JPEG);
    uploads.receive(row.id(), true, JPEG);
    return live.describePhoto(uploads.complete(row.id()), JPEG);
  }

  void play(Duration d) throws InterruptedException {
    long end = db.clock.nowMs() + d.toMillis();
    long beat = db.clock.nowMs();
    while (db.clock.nowMs() < end) {
      db.clock.advance(Duration.ofMillis(500));
      if (db.clock.nowMs() - beat >= 30_000) {
        live.heartbeat();
        beat = db.clock.nowMs();
      }
      live.tick();
      Thread.sleep(1);
    }
  }

  long count(String sql, Object... args) {
    return db.sql.queryOne(sql, List.of(args), r -> r.lng("n")).orElseThrow();
  }

  @Test
  void residentsReactToTheDogWithinTheSession() throws Exception {
    var photo = uploaded();
    assertThat(photo.description()).contains("golden retriever");
    assertThat(looked).as("the model got the picture itself").singleElement()
        .satisfies(parts -> assertThat(parts).anyMatch(p -> p instanceof Part.Image i && i.mime().equals("image/jpeg")));

    String post = live.post("Meet the newest member of the family", photo);

    assertThat(db.sql.queryOne("SELECT kind, media_id FROM post WHERE id = ?", List.of(post), r -> r.str("kind") + "|" + r.str("media_id")).orElseThrow())
        .isEqualTo("PHOTO|" + photo.id());
    live.awaitIdle(Duration.ofSeconds(10));
    play(Duration.ofMinutes(10));
    live.awaitIdle(Duration.ofSeconds(10));
    play(Duration.ofMinutes(10));

    assertThat(count("SELECT COUNT(*) AS n FROM reaction WHERE target_id = ? AND air_status = 'AIRED'", post)).as("reactions within the session").isPositive();
    assertThat(count("SELECT COUNT(*) AS n FROM comment WHERE post_id = ? AND air_status = 'AIRED' AND body IS NOT NULL", post)).as("comments within the session").isPositive();
    assertThat(rendered).as("the writers know what's in the picture")
        .anyMatch(text -> text.contains("the photo shows: a golden retriever puppy asleep on a couch"));
  }

  /** Regression: some rolls left a post with no answer at all, which is the one thing generous attention (SPEC P3) rules out. */
  @Test
  void noPostIsEverMetWithSilence() throws Exception {
    var posts = new ArrayList<String>();
    for (String text : List.of("Long day.", "Anyone seen the lake today?", "Meh.", "Trying a new coffee place tomorrow", "ok")) {
      posts.add(live.post(text, null));
      live.awaitIdle(Duration.ofSeconds(10));
      play(Duration.ofMinutes(7));
    }
    live.awaitIdle(Duration.ofSeconds(10));
    play(Duration.ofMinutes(8));
    live.awaitIdle(Duration.ofSeconds(10));
    play(Duration.ofMinutes(8));
    for (String post : posts) {
      assertThat(count("SELECT COUNT(*) AS n FROM reaction WHERE target_id = ? AND air_status = 'AIRED'", post)).as("reactions on " + post).isGreaterThanOrEqualTo(3);
      assertThat(count("SELECT COUNT(*) AS n FROM comment WHERE post_id = ? AND air_status = 'AIRED'", post)).as("comments on " + post).isPositive();
    }
  }

  @Test
  void aPhotoWithNoCaptionStillWorks_AndNeedsNoAppraisalOfWords() throws Exception {
    var photo = uploaded();
    String post = live.post("", photo);
    live.awaitIdle(Duration.ofSeconds(10));
    assertThat(db.sql.queryOne("SELECT body FROM post WHERE id = ?", List.of(post), r -> r.str("body")).orElseThrow()).isEmpty();
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE id = ? AND air_status = 'AIRED'", post)).isEqualTo(1);
  }

  @Test
  void whenVisionFailsThePhotoStillPosts_JustWithLessToGoOn() throws Exception {
    visionFails = true;
    var photo = uploaded();
    assertThat(photo.description()).isEqualTo("a photo");
    assertThat(photo.status()).isEqualTo(MediaRow.READY);
    String post = live.post("Look at this", photo);
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE id = ? AND media_id IS NOT NULL", post)).isEqualTo(1);
  }

  @Test
  void aPhotoCountsTowardHowThePostComesAcross() {
    var text = Appraiser.reading(Appraiser.heuristic("meh", false));
    var with = Appraiser.withPhoto(text, DOG);
    assertThat(with.features().get("warmth")).isEqualTo(0.9);
    assertThat(with.features().get("positivity")).isEqualTo(0.9);
    assertThat(with.topics()).contains("pets");
    var quiet = new Appraiser.PhotoAppraisal("x", new ArrayList<>(), 0, 0, 0, 0, 0, 0, 0);
    var warm = Appraiser.withPhoto(Appraiser.reading(Appraiser.heuristic("thank you so much, love you all!", false)), quiet);
    assertThat(warm.features().get("warmth")).as("a neutral photo doesn't erase warm words").isPositive();
  }
}
