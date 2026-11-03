package app.radiance.batch;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.kernel.AirStatus;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.RenderStatus;
import app.radiance.media.ImageGenerator;
import app.radiance.media.LocalFileMediaStore;
import app.radiance.persistence.TestDatabase;
import app.radiance.persistence.UnitOfWork;
import app.radiance.sim.Brief;
import app.radiance.sim.Knobs;
import app.radiance.sim.PhotoPrompts;
import app.radiance.sim.WorldEngine;
import app.radiance.sim.model.Thing;
import app.radiance.social.PostRow;
import app.radiance.social.SocialRepository;
import app.radiance.world.genesis.SeedImporter;
import app.radiance.world.genesis.SkeletonGenerator;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The batch's pictures (SPEC §5.13, §12 M12's AC): continuity, priority, budgets, and quota exhaustion degrading gracefully. */
class ImageWorkTests {

  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};

  @TempDir Path dir;
  TestDatabase db;
  WorldEngine engine;
  String author;
  long now;

  /** Records what it's asked for; runs out after {@code allowance} images. */
  static final class Stub implements ImageGenerator {
    final List<String> prompts = new ArrayList<>();
    final List<Long> seeds = new ArrayList<>();
    int allowance = Integer.MAX_VALUE;
    boolean broken;
    boolean real = true;

    @Override
    public Generated generate(String prompt, long seed) {
      if (broken) throw new IllegalStateException("provider is down");
      if (allowance-- <= 0) throw new QuotaExceeded("out of neurons");
      prompts.add(prompt);
      seeds.add(seed);
      return new Generated(JPEG, "image/jpeg");
    }

    @Override
    public boolean real() {
      return real;
    }
  }

  @BeforeEach
  void world() {
    db = TestDatabase.leased(dir.resolve("db"));
    var seed = SkeletonGenerator.generate(NightlyBatchTests.LIB, new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
    new SeedImporter(db.sql, db.lane, NightlyBatchTests.LIB).importSeed(seed, db.clock.nowMs());
    engine = new WorldEngine(db.sql, db.lane, NightlyBatchTests.LIB, Knobs.defaults());
    author = engine.world().residents().getFirst().id;
    now = db.clock.nowMs();
  }

  @AfterEach
  void close() {
    db.close();
  }

  ImageWork work(ImageGenerator g, int perDay, int neurons) {
    return new ImageWork(db.sql, db.lane, new LocalFileMediaStore(dir.resolve("media")), g, NightlyBatchTests.LIB, db.clock,
        new ImageWork.Limits(perDay, neurons, 60));
  }

  void photoPost(String id, int importance, String arcId, Fidelity fidelity, String media) {
    var brief = new Brief(null, "DAILY", "a snapshot", null, null, null, List.of(), 0, 0, Map.of("media", media));
    var post = new PostRow(id, author, now + 60_000, PostRow.Kind.PHOTO, PostRow.Audience.FRIENDS, ContentOrigin.SCRIPT, AirStatus.SCRIPTED, null,
        RenderStatus.PLANNED, brief.json(), null, null, null, null, null, importance, fidelity, "{}", 0);
    db.commit(new SocialRepository(db.sql).savePosts(new UnitOfWork(), List.of(post)));
    if (arcId != null) {
      db.commit(new UnitOfWork().add("PRAGMA defer_foreign_keys = ON").add("UPDATE post SET arc_id = ? WHERE id = ?", arcId, id));
    }
  }

  String mediaStatus(String postId) {
    return db.sql.queryOne("SELECT m.status AS s FROM post p JOIN media m ON m.id = p.media_id WHERE p.id = ?", List.of(postId),
        r -> r.str("s")).orElse(null);
  }

  long count(String sql) {
    return db.sql.queryOne(sql, List.of(), r -> r.lng("n")).orElseThrow();
  }

  ImageWork.Report run(ImageGenerator g, int perDay, int neurons) {
    return work(g, perDay, neurons).run(engine.world(), now);
  }

  String[] avatarOf(String actorId) {
    return db.sql.queryOne("SELECT m.storage_key AS full_key, m.thumb_key AS thumb, m.status AS s FROM actor a JOIN media m ON m.id = a.avatar_media_id WHERE a.id = ?",
        List.of(actorId), r -> new String[] {r.str("full_key"), r.str("thumb"), r.str("s")}).orElse(null);
  }

  /** An approved picture: nothing generated for them, and the circle shows the face rather than the whole hiking photo. */
  @Test
  void anApprovedPictureBecomesTheAvatar_WithItsFaceAsTheThumbnail() throws Exception {
    var files = java.nio.file.Files.createDirectories(dir.resolve("avatars"));
    var person = engine.world().residents().getFirst();
    java.nio.file.Files.write(files.resolve(person.handle + ".jpg"), JPEG);
    byte[] face = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};
    java.nio.file.Files.write(files.resolve(person.handle + ".thumb.jpg"), face);
    var stub = new Stub();
    var store = new LocalFileMediaStore(dir.resolve("media"));
    var work = work(stub, 0, 9000).withAvatarFiles(files);
    assertThat(work.run(engine.world(), now).avatars()).isGreaterThanOrEqualTo(1);
    var avatar = avatarOf(person.id);
    assertThat(avatar[2]).isEqualTo("READY");
    assertThat(store.get(avatar[0]).orElseThrow()).isEqualTo(JPEG);
    assertThat(store.get(avatar[1]).orElseThrow()).isEqualTo(face);
    assertThat(stub.prompts).hasSize(engine.world().residents().size() - 1);
  }

  @Test
  void anUnchangedPictureIsLeftAlone_AndAChangedOneReplacesTheAvatar() throws Exception {
    var files = java.nio.file.Files.createDirectories(dir.resolve("avatars"));
    var person = engine.world().residents().getFirst();
    var file = files.resolve(person.handle + ".jpg");
    java.nio.file.Files.write(file, JPEG);
    var work = work(new Stub(), 0, 9000).withAvatarFiles(files);
    work.run(engine.world(), now);
    String first = avatarOf(person.id)[0];
    work.run(engine.world(), now);
    assertThat(avatarOf(person.id)[0]).isEqualTo(first);
    byte[] next = java.util.Arrays.copyOf(JPEG, JPEG.length + 1);
    java.nio.file.Files.write(file, next);
    work.run(engine.world(), now);
    assertThat(avatarOf(person.id)[0]).isNotEqualTo(first);
    assertThat(new LocalFileMediaStore(dir.resolve("media")).get(avatarOf(person.id)[0]).orElseThrow()).isEqualTo(next);
  }

  /** Regression: Kristen wrote about salmon and the generic prompt gave her eggs. */
  @Test
  void aPhotoShowsWhatItsWrittenPostSays() {
    photoPost("p", 3, null, Fidelity.FULL, "a meal on a table, natural light");
    db.commit(new UnitOfWork().add("UPDATE post SET body = ? WHERE id = ?", "Salmon and broccoli off the sheet pan, thanks @jake.zielinski", "p"));
    var stub = new Stub();
    run(stub, 12, 9000);
    String prompt = stub.prompts.stream().filter(x -> x.contains("Salmon")).findFirst().orElseThrow();
    assertThat(prompt).contains("Salmon and broccoli off the sheet pan, thanks a friend").doesNotContain("a meal on a table", "@jake");
    // Regression: asked for "the photo with this caption", FLUX 3 painted the caption across the picture.
    assertThat(prompt).contains("no words, captions or overlays").doesNotContain("with this caption");
  }

  ImageWork.Report runWithPremium(Stub everyday, ImageGenerator better, int perRun) {
    return work(everyday, 12, 9000).withPremium(new app.radiance.media.PremiumImages(better, perRun)).run(engine.world(), now);
  }

  @Test
  void onlyPicturesPeopleKeepSeeingGetThePremiumModel() {
    photoPost("meal", 1, null, Fidelity.FULL, "a plain meal for everyday");
    photoPost("big", 5, null, Fidelity.FULL, "a big lasting moment");
    var everyday = new Stub();
    var better = new Stub();
    assertThat(runWithPremium(everyday, better, 100).premium()).isEqualTo(better.prompts.size());
    assertThat(better.prompts).anyMatch(x -> x.contains("a big lasting moment")).noneMatch(x -> x.contains("a plain meal"));
    assertThat(everyday.prompts).anyMatch(x -> x.contains("a plain meal")).noneMatch(x -> x.contains("a big lasting moment"));
  }

  @Test
  void pastItsLimit_OrOutOfCredits_APictureUsesTheEverydayModelInstead() {
    photoPost("big", 5, null, Fidelity.FULL, "a big lasting moment");
    var everyday = new Stub();
    var spent = new Stub();
    spent.allowance = 0;
    assertThat(runWithPremium(everyday, spent, 100).deferred()).isZero();
    assertThat(mediaStatus("big")).isEqualTo("READY");
    assertThat(everyday.prompts).anyMatch(x -> x.contains("a big lasting moment"));
    assertThat(spent.prompts).isEmpty();
  }

  @Test
  void aPhotoPostGainsItsPicture_ThePromptCarriesTheRules() {
    photoPost("p", 3, null, Fidelity.FULL, "houseplants on a sunny windowsill");
    var stub = new Stub();
    var report = run(stub, 12, 9000);
    assertThat(report.photos()).isEqualTo(1);
    assertThat(mediaStatus("p")).isEqualTo("READY");
    String prompt = stub.prompts.stream().filter(x -> x.contains("houseplants")).findFirst().orElseThrow();
    assertThat(prompt).contains("No identifiable faces", "no children", "no real people", "no logos", "no text");
  }

  /** SPEC §12 M12 AC: Jesse's car is recognizably the same car across three posts. */
  @Test
  void anEntityLooksTheSameInEveryPost() {
    var w = engine.world();
    w.things.put("car1", new Thing("car1", author, app.radiance.story.EntityRow.Kind.CAR, "the Civic", "a dented 2009 teal Honda Civic with one black door", 4242L, Map.of(), "OWNED", now, now));
    String template = "{entity.visual_description}, ";
    var defId = "car_saga";
    photoPostWithDef("a", defId, template + "residential driveway, overcast");
    photoPostWithDef("b", defId, template + "on a tow truck at dusk");
    photoPostWithDef("c", defId, template + "parked outside a diner");
    var stub = new Stub();
    run(stub, 12, 9000);
    var carPrompts = stub.prompts.stream().filter(p -> p.contains("teal Honda Civic")).toList();
    assertThat(carPrompts).hasSize(3).allMatch(p -> p.contains("a dented 2009 teal Honda Civic with one black door"));
    int first = stub.prompts.indexOf(carPrompts.getFirst());
    assertThat(stub.seeds.subList(first, first + 3)).containsOnly(4242L);
    assertThat(count("SELECT COUNT(*) AS n FROM media WHERE entity_id = 'car1'")).isEqualTo(3);
  }

  void photoPostWithDef(String id, String defId, String media) {
    var brief = new Brief(defId, "DAILY", "the car", null, null, null, List.of(), 0, 0, Map.of("media", media));
    var post = new PostRow(id, author, now + 60_000, PostRow.Kind.PHOTO, PostRow.Audience.FRIENDS, ContentOrigin.SCRIPT, AirStatus.SCRIPTED, null,
        RenderStatus.PLANNED, brief.json(), null, null, null, null, null, 3, Fidelity.FULL, "{}", 0);
    db.commit(new SocialRepository(db.sql).savePosts(new UnitOfWork(), List.of(post)));
  }

  @Test
  void anEntityThatsGoneMeansNoPicture_NotAPromptWithAHoleInIt() {
    photoPostWithDef("p", "car_saga", "{entity.visual_description} on a tow truck");
    engine.world().things.values().removeIf(t -> t.ownerId.equals(author));
    var stub = new Stub();
    run(stub, 12, 9000);
    assertThat(stub.prompts).noneMatch(x -> x.contains("{entity"));
    assertThat(mediaStatus("p")).isNull();
  }

  /** SPEC §12 M12 AC: quota exhaustion degrades gracefully. */
  @Test
  void whenTheProviderRunsOutThePostAirsWithoutItsPhoto_AndGainsItLater() {
    photoPost("first", 5, null, Fidelity.FULL, "houseplants on a sunny windowsill");
    photoPost("second", 3, null, Fidelity.FULL, "a tidy room with afternoon light");
    var stub = new Stub();
    stub.real = false; // no avatars in the way: this is about the photos
    stub.allowance = 1;
    var report = run(stub, 12, 9000);
    assertThat(report.photos()).isEqualTo(1);
    assertThat(report.deferred()).isEqualTo(1);
    assertThat(mediaStatus("first")).isEqualTo("READY");
    assertThat(mediaStatus("second")).isEqualTo("DEFERRED");

    // it airs regardless, with no picture yet
    db.commit(new UnitOfWork().add("UPDATE post SET body = 'plants!', render_status = 'RENDERED' WHERE id IN ('first', 'second')"));
    db.clock.set(now + 120_000);
    engine.advanceTo(now + 120_000);
    assertThat(count("SELECT COUNT(*) AS n FROM post WHERE id = 'second' AND air_status = 'AIRED'")).isEqualTo(1);

    // allowance back: a later run finishes it, reusing the same row
    stub.allowance = Integer.MAX_VALUE;
    long mediaBefore = count("SELECT COUNT(*) AS n FROM media");
    var later = run(stub, 12, 9000);
    assertThat(later.photos()).isEqualTo(1);
    assertThat(mediaStatus("second")).isEqualTo("READY");
    assertThat(count("SELECT COUNT(*) AS n FROM media")).isEqualTo(mediaBefore);
  }

  @Test
  void aBrokenProviderIsTriedAFewTimesThenLeftForLater_AndNeverThrows() {
    for (int i = 0; i < 6; i++) photoPost("p" + i, 3, null, Fidelity.FULL, "houseplants on a sunny windowsill");
    var stub = new Stub();
    stub.real = false;
    stub.broken = true;
    var report = run(stub, 12, 9000);
    assertThat(report.failed()).isEqualTo(3);
    assertThat(count("SELECT COUNT(*) AS n FROM media WHERE status = 'DEFERRED'")).isEqualTo(3);
    stub.broken = false;
    assertThat(run(stub, 12, 9000).photos()).isEqualTo(6);
    assertThat(count("SELECT COUNT(*) AS n FROM media WHERE status = 'READY'")).isEqualTo(6);
  }

  @Test
  void theDailyCountLimitsPhotos_AndTheMostImportantGoFirst() {
    photoPost("minor", 1, null, Fidelity.FULL, "houseplants on a sunny windowsill");
    photoPost("major", 6, null, Fidelity.FULL, "a tidy room with afternoon light");
    var stub = new Stub();
    stub.real = false;
    assertThat(run(stub, 1, 9000).photos()).isEqualTo(1);
    assertThat(mediaStatus("major")).isEqualTo("READY");
    assertThat(mediaStatus("minor")).isNull();
    assertThat(run(stub, 1, 9000).photos()).as("the day's count is used").isZero();
    db.clock.set(now + 86_400_000L);
    assertThat(run(stub, 1, 9000).photos()).as("a new day").isEqualTo(1);
  }

  @Test
  void theNeuronBudgetStopsGeneration_ACallThatWouldGoOverIsntMade() {
    for (int i = 0; i < 3; i++) photoPost("p" + i, 3, null, Fidelity.FULL, "houseplants on a sunny windowsill");
    db.commit(new UnitOfWork().add("UPDATE actor SET avatar_media_id = 'x'").add(
        "INSERT INTO media (id, kind, origin, status, created_at) VALUES ('x', 'AVATAR', 'UPLOAD', 'READY', 1)"));
    var stub = new Stub();
    var report = run(stub, 12, 130); // room for two at 60 each
    assertThat(report.photos()).isEqualTo(2);
    assertThat(stub.prompts).hasSize(2);
    assertThat(report.deferred()).isZero();
  }

  @Test
  void lowFidelityPostsDontSpendTheBudget() {
    photoPost("summary", 3, null, Fidelity.SUMMARY, "houseplants on a sunny windowsill");
    photoPost("reduced", 3, null, Fidelity.REDUCED, "a tidy room with afternoon light");
    var stub = new Stub();
    stub.real = false;
    assertThat(run(stub, 12, 9000).photos()).isZero();
    assertThat(mediaStatus("summary")).isNull();
    assertThat(mediaStatus("reduced")).as("REDUCED: arc milestones only").isNull();
  }

  @Test
  void aDroppedPostGetsNoPicture_NorDoesRsOwn() {
    photoPost("gone", 3, null, Fidelity.FULL, "houseplants on a sunny windowsill");
    db.commit(new UnitOfWork().add("UPDATE post SET air_status = 'DROPPED' WHERE id = 'gone'"));
    photoPost("mine", 3, null, Fidelity.FULL, "a tidy room with afternoon light");
    db.commit(new UnitOfWork().add("UPDATE post SET origin = 'USER' WHERE id = 'mine'"));
    var stub = new Stub();
    stub.real = false;
    assertThat(run(stub, 12, 9000).photos()).isZero();
  }

  @Test
  void aRealGeneratorMakesAvatars_OnceEach_AndThePlaceholderDoesnt() {
    var fake = new Stub();
    fake.real = false;
    run(fake, 12, 90_000);
    assertThat(fake.prompts).isEmpty();
    assertThat(count("SELECT COUNT(*) AS n FROM actor WHERE avatar_media_id IS NOT NULL")).isZero();

    var real = new Stub();
    var report = run(real, 12, 90_000);
    long residents = count("SELECT COUNT(*) AS n FROM actor WHERE kind = 'BOT'");
    assertThat(report.avatars()).isEqualTo((int) residents);
    assertThat(count("SELECT COUNT(*) AS n FROM actor WHERE kind = 'BOT' AND avatar_media_id IS NOT NULL")).isEqualTo(residents);
    assertThat(real.prompts).allMatch(p -> p.contains("An invented person, not anyone real") && p.matches(".*\\b(man|woman)\\b.*"));
    assertThat(run(real, 12, 90_000).avatars()).as("not again").isZero();
  }

  @Test
  void anAvatarThatRunsIntoTheQuotaIsFinishedNextTime() {
    var stub = new Stub();
    stub.allowance = 2;
    var report = run(stub, 12, 90_000);
    assertThat(report.avatars()).isEqualTo(2);
    assertThat(report.deferred()).isEqualTo(1);
    stub.allowance = Integer.MAX_VALUE;
    run(stub, 12, 90_000);
    long residents = count("SELECT COUNT(*) AS n FROM actor WHERE kind = 'BOT'");
    assertThat(count("SELECT COUNT(*) AS n FROM actor a JOIN media m ON m.id = a.avatar_media_id WHERE a.kind = 'BOT' AND m.status = 'READY'")).isEqualTo(residents);
  }

  @Test
  void outdoorShotsCarryTheWeather_IndoorOnesDont() {
    var w = engine.world();
    var outdoor = new Brief(null, "DAILY", "x", null, null, null, List.of(), 0, 0, Map.of("media", "a snowy Chicago side street"));
    var indoor = new Brief(null, "DAILY", "x", null, null, null, List.of(), 0, 0, Map.of("media", "houseplants on a windowsill"));
    assertThat(PhotoPrompts.of(w, NightlyBatchTests.LIB, "p", author, null, outdoor, "28°F, light snow").orElseThrow().prompt()).contains("28°F, light snow");
    assertThat(PhotoPrompts.of(w, NightlyBatchTests.LIB, "p", author, null, indoor, "28°F, light snow").orElseThrow().prompt()).doesNotContain("28°F");
  }

  @Test
  void writersNeverSeeARawPlaceholder() {
    var w = engine.world();
    w.things.put("pet", new Thing("pet", author, app.radiance.story.EntityRow.Kind.PET, "Biscuit", "a scruffy brown terrier", 9L, Map.of(), "OWNED", now, now));
    assertThat(PhotoPrompts.forWriters(w, NightlyBatchTests.LIB, author, "pet_photo", null, "{entity.visual_description} asleep")).isEqualTo("a scruffy brown terrier asleep");
    assertThat(PhotoPrompts.forWriters(w, NightlyBatchTests.LIB, "nobody", "pet_photo", null, "{entity.visual_description} asleep")).doesNotContain("{entity");
    assertThat(PhotoPrompts.forWriters(w, NightlyBatchTests.LIB, author, null, null, "a plate of food")).isEqualTo("a plate of food");
    assertThat(PhotoPrompts.forWriters(w, NightlyBatchTests.LIB, author, null, null, null)).isNull();
  }
}
