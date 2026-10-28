package app.radiance.sim;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.kernel.AirStatus;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.RenderStatus;
import app.radiance.kernel.Visibility;
import app.radiance.persistence.TestDatabase;
import app.radiance.persistence.UnitOfWork;
import app.radiance.sim.model.Person;
import app.radiance.social.CommentRow;
import app.radiance.social.PostRow;
import app.radiance.social.ReactionRow;
import app.radiance.social.SocialRepository;
import app.radiance.story.EventRepository;
import app.radiance.story.EventRow;
import app.radiance.weather.WeatherHour;
import app.radiance.weather.WeatherRepository;
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

/** Airing (SPEC §5.1, invariants 8 and 9): validate, apply or drop with dependents, never early. */
class AiringTests {

  static final ContentLibrary LIB = ClasspathContent.load(List.of(
      new ContentProperties.BlacklistEntry("pregnancy", List.of("pregnant"))));
  static final long T = TestDatabase.START;
  static final long MIN = 60_000L;

  @TempDir Path dir;
  TestDatabase db;
  WorldEngine engine;
  Map<String, Person> h;

  @BeforeEach
  void world() {
    db = TestDatabase.leased(dir);
    var seed = SkeletonGenerator.generate(LIB, new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
    new SeedImporter(db.sql, db.lane, LIB).importSeed(seed, T);
    engine = new WorldEngine(db.sql, db.lane, LIB, Knobs.defaults());
    h = engine.world().byHandle();
  }

  @AfterEach
  void close() {
    db.close();
  }

  String id(String handle) {
    return h.get(handle).id;
  }

  static PostRow post(String id, String author, long t, String eventId, Brief brief) {
    return new PostRow(id, author, t, PostRow.Kind.STATUS, PostRow.Audience.FRIENDS, ContentOrigin.SCRIPT,
        AirStatus.SCRIPTED, null, RenderStatus.PLANNED, brief.json(), null, null, null, null, eventId, 3, Fidelity.FULL,
        "{}", 0);
  }

  static CommentRow comment(String id, String post, String parent, String author, long t, Brief brief) {
    return new CommentRow(id, post, parent, author, t, ContentOrigin.SCRIPT, AirStatus.SCRIPTED, null, null,
        RenderStatus.PLANNED, brief.json(), null, "{}");
  }

  static Brief stance(String stance, String toward) {
    return new Brief(null, null, null, stance, toward, null, List.of("cars"), 0.3, 0, Map.of());
  }

  void save(List<EventRow> events, List<PostRow> posts, List<CommentRow> comments, List<ReactionRow> reactions) {
    var uow = new UnitOfWork();
    new EventRepository(db.sql).save(uow, events);
    var social = new SocialRepository(db.sql);
    social.savePosts(uow, posts);
    social.saveComments(uow, comments);
    social.saveReactions(uow, reactions);
    db.commit(uow);
  }

  AirStatus postStatus(String id) {
    return new SocialRepository(db.sql).post(id).orElseThrow().airStatus();
  }

  AirStatus commentStatus(String id) {
    return new SocialRepository(db.sql).comment(id).orElseThrow().airStatus();
  }

  @Test
  void aThreadAirsInOrderAndMovesMetersThroughTemperament() {
    String jesse = id("jesse.hensley"), jake = id("jake.zielinski"), kendra = id("kendra.gaines");
    var before = engine.world().edge(jesse, jake).affinity;
    save(List.of(), List.of(post("p1", jesse, T + 10 * MIN, null, Brief.NONE)),
        List.of(comment("c1", "p1", null, jake, T + 20 * MIN, stance("TROLL", jesse)),
            comment("c2", "p1", "c1", jesse, T + 30 * MIN, stance("JOKE", jake))),
        List.of(new ReactionRow(ReactionRow.Target.POST, "p1", kendra, ReactionRow.Kind.HAHA, T + 25 * MIN,
            AirStatus.SCRIPTED)));

    var r = engine.advanceTo(T + 40 * MIN);
    assertThat(r.aired()).isEqualTo(4);
    assertThat(r.dropped()).isZero();
    assertThat(postStatus("p1")).isEqualTo(AirStatus.AIRED);
    assertThat(commentStatus("c2")).isEqualTo(AirStatus.AIRED);
    var w = engine.world();
    // troll −5 lands ×0.4 on a TYPICAL resident (mood shifts it a little), then the joke reply
    assertThat(w.edge(jesse, jake).affinity).isLessThan(before);
    assertThat(w.clashes).containsKey(app.radiance.sim.model.Edge.pair(jesse, jake));
    assertThat(w.person(jake).interactionsOn(w.day(T + 20 * MIN))).isEqualTo(1);
    assertThat(w.items.get("p1").reactionCounts).containsEntry("HAHA", 1);
    assertThat(db.count("memory")).isGreaterThanOrEqualTo(2);
    assertThat(w.time).isEqualTo(T + 40 * MIN);

    // reloading gives the same committed world
    engine.reload();
    assertThat(engine.world().edge(jesse, jake).affinity).isEqualTo(Math.round(w.edge(jesse, jake).affinity * 10_000)
        / 10_000.0);
  }

  @Test
  void aDroppedEventTakesItsPostCommentsAndReactionsWithIt() {
    String sam = id("samantha.dvorak"), gary = id("gary.moran");
    long t = T + 2 * 3_600_000L;
    // the forecast promised snow; the latest data says otherwise
    var day = engine.world().date(t);
    var hours = new ArrayList<WeatherHour>();
    long start = day.atStartOfDay(engine.world().zone).toInstant().toEpochMilli();
    for (int i = 0; i < 24; i++) hours.add(new WeatherHour(start + i * 3_600_000L, 36, 30, 0, 0, 5, 3,
        WeatherHour.Kind.PAST, T));
    db.commit(new WeatherRepository(db.sql).save(new UnitOfWork(), hours));
    var payload = new EventPayload("DAILY", Map.of("actor", sam), Map.of(), null, null, List.of(), "snow_day",
        "snow day dibs");
    var event = new EventRow("e1", t, "SNOW_DAY_DIBS", "snow_day_dibs", sam, null, null, 3, Visibility.PUBLIC,
        EventRow.Source.STORYTELLER, AirStatus.SCRIPTED, payload.json(), null);
    save(List.of(event), List.of(post("p2", sam, t, "e1", Brief.NONE)),
        List.of(comment("c3", "p2", null, gary, t + 5 * MIN, stance("JOKE", sam)),
            comment("c4", "p2", "c3", sam, t + 9 * MIN, stance("JOKE", gary))),
        List.of(new ReactionRow(ReactionRow.Target.COMMENT, "c3", sam, ReactionRow.Kind.LIKE, t + 6 * MIN,
            AirStatus.SCRIPTED)));

    var r = engine.advanceTo(t + 3_600_000L);
    assertThat(r.dropped()).isEqualTo(5);
    assertThat(r.dropReasons().getFirst()).contains("snow_day");
    assertThat(postStatus("p2")).isEqualTo(AirStatus.DROPPED);
    assertThat(commentStatus("c4")).isEqualTo(AirStatus.DROPPED);
    assertThat(new SocialRepository(db.sql).reactionsTo(ReactionRow.Target.COMMENT, "c3").getFirst().airStatus())
        .isEqualTo(AirStatus.DROPPED);
  }

  @Test
  void anArcTransitionAirsOnlyFromThePhaseItWasPlannedFrom() {
    var w = engine.world();
    var romance = w.arcs.values().stream().filter(a -> a.defId.equals("romance") && a.phase.equals("secret_dating"))
        .findFirst().orElseThrow();
    String megan = id("megan.quinlan");
    var stale = new EventPayload.ArcChange(romance.id, "romance", "TRANSITION", "talking", "soft_launch", "PUBLIC",
        T + 9 * 86_400_000L, "ACTIVE", List.of(), romance.roles, Map.of(), null);
    var good = new EventPayload.ArcChange(romance.id, "romance", "TRANSITION", "secret_dating", "soft_launch",
        "PUBLIC", T + 9 * 86_400_000L, "ACTIVE", List.of(megan), romance.roles,
        Map.of(), "Jessica and Nick: romance (soft launch)");
    var effects = Map.<String, Object>of("mood", 0.3, "add_constraints", List.of("budget_tight:30d"));
    long t = T + 3 * 3_600_000L;
    save(List.of(
        arcEvent("e2", t, romance.id, new EventPayload("LIFE", romance.roles, Map.of(), stale, null, List.of(), null, null)),
        arcEvent("e3", t + MIN, romance.id, new EventPayload("LIFE", romance.roles, effects, good, null, List.of(), null,
            null)),
        arcEvent("e4", t + 2 * 3_600_000L, romance.id, new EventPayload("LIFE", romance.roles, Map.of(),
            new EventPayload.ArcChange(romance.id, "romance", "TRANSITION", "soft_launch", "official", "PUBLIC", null,
                "ACTIVE", List.of(), romance.roles, Map.of(), null), null, List.of(), null, null))),
        List.of(), List.of(), List.of());

    var r = engine.advanceTo(t + 3_600_000L);
    assertThat(r.dropped()).isEqualTo(1);
    var arc = engine.world().arcs.get(romance.id);
    assertThat(arc.phase).isEqualTo("soft_launch");
    assertThat(arc.visibility).isEqualTo(Visibility.PUBLIC);
    assertThat(engine.world().knows("ARC", romance.id, megan)).isTrue();
    String jessica = id("jessica.vogel");
    assertThat(engine.world().activeTags(jessica, t + 2 * MIN, LIB)).contains("budget_tight");
    assertThat(engine.world().activeTags(jessica, t + 31 * 86_400_000L, LIB)).doesNotContain("budget_tight");
    // no spoilers: the next transition is still only scripted
    assertThat(new EventRepository(db.sql).find("e4").orElseThrow().airStatus()).isEqualTo(AirStatus.SCRIPTED);
    engine.reload();
    assertThat(engine.world().arcs.get(romance.id).phase).isEqualTo("soft_launch");
    assertThat(engine.world().time).isEqualTo(t + 3_600_000L);
  }

  @Test
  void aResidentOutOfAttentionDoesNotComment() {
    String deepa = id("deepa.kulkarni"), kristen = id("kristen.wagner");
    var w = engine.world();
    var d = w.person(deepa);
    String day = w.day(T + 10 * MIN);
    while (d.interactionsOn(day) < d.routine.attentionBudget()) d.countInteraction(day);
    save(List.of(), List.of(post("p3", kristen, T + 10 * MIN, null, Brief.NONE)),
        List.of(comment("c5", "p3", null, deepa, T + 15 * MIN, stance("SUPPORT", kristen))), List.of());
    var r = engine.advanceTo(T + 20 * MIN);
    assertThat(r.dropReasons()).anyMatch(s -> s.contains("out of attention"));
    assertThat(commentStatus("c5")).isEqualTo(AirStatus.DROPPED);
  }

  static EventRow arcEvent(String id, long t, String arcId, EventPayload p) {
    return new EventRow(id, t, "ARC_TRANSITION", "romance", p.participants().get("a"), null, arcId, 6,
        Visibility.PUBLIC, EventRow.Source.ARC, AirStatus.SCRIPTED, p.json(), null);
  }
}
