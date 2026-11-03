package app.radiance.batch;

import app.radiance.content.ContentLibrary;
import app.radiance.kernel.Json;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Ulid;
import app.radiance.kernel.WallClock;
import app.radiance.media.ImageGenerator;
import app.radiance.media.MediaRepository;
import app.radiance.media.MediaRow;
import app.radiance.media.MediaStore;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.Brief;
import app.radiance.sim.PhotoPrompts;
import app.radiance.sim.model.SimWorld;
import app.radiance.social.SocialRepository;
import app.radiance.weather.WeatherBook;
import app.radiance.weather.WeatherHour;
import app.radiance.weather.WeatherRepository;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The batch's pictures (SPEC §5.13): avatars, then scripted photos, arc milestones first. Within a daily count and a neuron
 * budget. When the provider is out of allowance, what's left waits for a later run: a post airs without its photo, and gains
 * it when it's ready.
 */
public final class ImageWork {

  public record Report(int photos, int avatars, int deferred, int failed) {
    public boolean any() {
      return photos + avatars + deferred + failed > 0;
    }
  }

  /** The limits: the daily photo count and neuron budget are per UTC day, when Workers AI's allocation resets. */
  public record Limits(int photosPerDay, int neuronBudget, int neuronsPerImage) {}

  private static final Logger log = LoggerFactory.getLogger(ImageWork.class);
  private static final long DAY = 86_400_000L;
  private static final long LOOK_BACK = 2 * DAY;
  private static final int GIVE_UP_AFTER_FAILURES = 3;

  private final Sql sql;
  private final WriterLane lane;
  private final MediaStore store;
  private final ImageGenerator generator;
  private final ContentLibrary lib;
  private final WallClock clock;
  private volatile Limits limits;

  public ImageWork(Sql sql, WriterLane lane, MediaStore store, ImageGenerator generator, ContentLibrary lib, WallClock clock, Limits limits) {
    this.sql = sql;
    this.lane = lane;
    this.store = store;
    this.generator = generator;
    this.lib = lib;
    this.clock = clock;
    this.limits = limits;
  }

  /** New limits from the Director's console. */
  public void tune(Limits limits) {
    this.limits = limits;
  }

  public Limits limits() {
    return limits;
  }

  /** Runs on the batch's thread, outside the writer lane: each image takes seconds. */
  public Report run(SimWorld w, long worldNow) {
    var repo = new MediaRepository(sql);
    long wall = clock.nowMs();
    long today = wall - wall % DAY;
    int neurons = (int) Math.min(Integer.MAX_VALUE, repo.generatedSince(today)) * limits.neuronsPerImage();
    int[] count = {0, 0, 0, 0, neurons}; // photos, avatars, deferred, failed, neurons used
    boolean open = avatars(w, worldNow, count);
    if (open) photos(w, worldNow, today, count);
    var report = new Report(count[0], count[1], count[2], count[3]);
    if (report.any()) log.info("Images: {}", report);
    return report;
  }

  private boolean affordable(int[] count) {
    return count[4] + limits.neuronsPerImage() <= limits.neuronBudget();
  }

  private boolean avatars(SimWorld w, long worldNow, int[] count) {
    if (!generator.real()) return true; // the placeholder isn't better than initials
    var missing = sql.query("SELECT a.id, a.gender, a.birth_date, a.occupation, p.identity_json FROM actor a JOIN persona p ON p.actor_id = a.id"
        + " LEFT JOIN media m ON m.id = a.avatar_media_id WHERE a.kind = 'BOT' AND (a.avatar_media_id IS NULL OR m.status <> 'READY') ORDER BY a.id",
        List.of(), r -> new String[] {r.str("id"), r.str("gender"), r.str("birth_date"), r.str("occupation"), r.str("identity_json")});
    int failures = 0;
    for (var a : missing) {
      if (!affordable(count)) return false;
      var row = pending("AVATAR", avatarPrompt(a, w.date(worldNow)), a[0].hashCode(), null);
      var uow = new MediaRepository(sql).save(new UnitOfWork(), row);
      uow.add("UPDATE actor SET avatar_media_id = ? WHERE id = ?", row.id(), a[0]);
      lane.run(() -> sql.commit(uow));
      switch (generate(row, count)) {
        case QUOTA -> {
          return false;
        }
        case FAILED -> {
          if (++failures >= GIVE_UP_AFTER_FAILURES) return false;
        }
        case DONE -> count[1]++;
      }
    }
    return true;
  }

  /** Reused wherever they appear. Avatars are the one place an identifiable (AI) face is allowed. */
  static String avatarPrompt(String[] a, LocalDate today) {
    String gender = "F".equals(a[1]) ? "woman" : "man";
    String heritage = a[4] == null ? "" : Json.tree(a[4]).path("heritage").asString("");
    int age = a[2] == null || a[2].length() < 10 ? 30 : Math.max(18, Period.between(LocalDate.parse(a[2]), today).getYears());
    return "Casual social media profile photo of an ordinary " + age + "-year-old " + (heritage.isBlank() ? "" : heritage + " ") + gender
        + (a[3] == null ? "" : ", a " + a[3].toLowerCase(java.util.Locale.ROOT)) + ", head and shoulders, natural light, relaxed expression."
        + " An invented person, not anyone real. No text, no logos.";
  }

  private void photos(SimWorld w, long worldNow, long today, int[] count) {
    long used = sql.queryOne("SELECT COUNT(*) AS n FROM media WHERE origin = 'GENERATED' AND kind = 'PHOTO' AND status = 'READY' AND created_at >= ?",
        List.of(today), r -> r.lng("n")).orElse(0L);
    int room = (int) Math.max(0, limits.photosPerDay() - used);
    if (room == 0) return;
    // what's left over from a quota comes round again, most important first
    var ids = sql.query("SELECT p.id FROM post p LEFT JOIN media m ON m.id = p.media_id WHERE p.kind = 'PHOTO' AND p.origin <> 'USER'"
        + " AND p.air_status <> 'DROPPED' AND p.created_at >= ? AND (p.fidelity = 'FULL' OR (p.fidelity = 'REDUCED' AND p.arc_id IS NOT NULL))"
        + " AND (p.media_id IS NULL OR m.status IN ('DEFERRED', 'PENDING')) ORDER BY (p.arc_id IS NULL), p.importance DESC, p.created_at, p.id LIMIT ?",
        List.of(worldNow - LOOK_BACK, room), r -> r.str("id"));
    if (ids.isEmpty()) return;
    var weather = new WeatherBook(w.zone, lib.weather()).putAll(new WeatherRepository(sql).between(worldNow - LOOK_BACK - WeatherHour.HOUR, worldNow + 3 * DAY));
    var social = new SocialRepository(sql);
    var repo = new MediaRepository(sql);
    int failures = 0;
    for (String id : ids) {
      if (!affordable(count)) return;
      var post = social.post(id).orElse(null);
      if (post == null) continue;
      String sky = weather.at(post.createdAt()).map(WeatherHour::describe).orElse(null);
      var photo = PhotoPrompts.of(w, lib, post.id(), post.authorId(), post.arcId(), Brief.parse(post.briefJson()), sky);
      if (photo.isEmpty()) continue;
      var existing = post.mediaId() == null ? null : repo.byId(post.mediaId()).orElse(null);
      var row = existing != null ? existing : pending("PHOTO", photo.get().prompt(), photo.get().seed(), photo.get().entityId());
      if (existing == null) {
        var uow = repo.save(new UnitOfWork(), row);
        uow.add("UPDATE post SET media_id = ? WHERE id = ?", row.id(), post.id());
        lane.run(() -> sql.commit(uow));
      }
      switch (generate(row, count)) {
        case QUOTA -> {
          return;
        }
        case FAILED -> {
          if (++failures >= GIVE_UP_AFTER_FAILURES) return;
        }
        case DONE -> count[0]++;
      }
    }
  }

  private MediaRow pending(String kind, String prompt, long seed, String entityId) {
    long now = clock.nowMs();
    String id = Ulid.next(now, RandomSource.seeded(System.nanoTime()).derive("media"));
    return new MediaRow(id, kind, "GENERATED", null, null, null, null, null, null, prompt, seed, entityId, prompt, MediaRow.PENDING, null, now);
  }

  private enum Outcome { DONE, QUOTA, FAILED }

  private Outcome generate(MediaRow row, int[] count) {
    var repo = new MediaRepository(sql);
    try {
      var image = generator.generate(row.prompt(), row.seed());
      String key = "generated/" + row.id();
      store.put(key, image.bytes());
      var ready = row.ready(key, key, image.mime(), image.bytes().length);
      lane.run(() -> sql.commit(repo.save(new UnitOfWork(), ready)));
      if (generator.real()) count[4] += limits.neuronsPerImage();
      return Outcome.DONE;
    } catch (ImageGenerator.QuotaExceeded e) {
      log.info("Image generation paused: {}", e.getMessage());
      lane.run(() -> sql.commit(repo.save(new UnitOfWork(), row.withStatus(MediaRow.DEFERRED))));
      count[2]++;
      return Outcome.QUOTA;
    } catch (java.io.IOException | RuntimeException e) {
      log.warn("Image generation failed for {}: {}", row.id(), e.toString());
      lane.run(() -> sql.commit(repo.save(new UnitOfWork(), row.withStatus(MediaRow.DEFERRED))));
      count[3]++;
      return Outcome.FAILED;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Outcome.QUOTA;
    }
  }
}
