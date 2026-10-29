package app.radiance.sim;

import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.content.Vocab.Stance;
import app.radiance.graph.Onboarding;
import app.radiance.kernel.AirStatus;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.ManualClock;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.RenderStatus;
import app.radiance.kernel.Ulid;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.OfflineGateway;
import app.radiance.news.FixtureHeadlines;
import app.radiance.news.Headline;
import app.radiance.news.HeadlineRepository;
import app.radiance.news.HeadlineSelection;
import app.radiance.persistence.JdbcSql;
import app.radiance.persistence.LeaseHolder;
import app.radiance.persistence.MigrationRunner;
import app.radiance.persistence.Schema;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.persistence.WriterLease;
import app.radiance.sim.model.Item;
import app.radiance.social.CommentRow;
import app.radiance.social.PostRow;
import app.radiance.social.SocialRepository;
import app.radiance.weather.FakeWeather;
import app.radiance.weather.WeatherBook;
import app.radiance.weather.WeatherRepository;
import app.radiance.world.genesis.SeedImporter;
import app.radiance.world.genesis.SkeletonGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The tuning harness (SPEC §10): an offline world from a genesis seed, nightly scripts written at
 * 03:00 and aired through the day, and a scripted R who posts, comments and replies in sessions in
 * between. Everything goes through the same engine, airing and persistence the app uses; nothing
 * calls a model. {@link FeelReport} reads the result.
 */
public final class Simulation implements AutoCloseable {

  /**
   * @param afterBatch runs after each nightly script is written — the render step, when a tool that can
   *     render (narrative) drives the simulation
   */
  public record Options(long seed, int days, Persona persona, Path dir, Knobs knobs, ContinuityAuditor.Mode audit,
      java.util.function.Consumer<Simulation> afterBatch) {
    public static Options of(long seed, int days, Persona persona, Path dir) {
      return new Options(seed, days, persona, dir, Knobs.defaults(), ContinuityAuditor.Mode.THROW, s -> { });
    }

    public Options rendering(java.util.function.Consumer<Simulation> render) {
      return new Options(seed, days, persona, dir, knobs, audit, render);
    }
  }

  /** One world-day of the run. */
  public record Day(LocalDate date, Fidelity fidelity, String planHash, int planned, int aired, int dropped,
      long rowsWritten, List<String> findings, List<String> dropReasons) {}

  static final long MIN = 60_000L;
  static final long HOUR = 60 * MIN;
  static final long DAY = 24 * HOUR;
  static final LocalDate START = LocalDate.of(2026, 10, 27);
  static final ZoneId ZONE = ZoneId.of("America/Chicago");

  public final Options options;
  public final ContentLibrary lib;
  public final JdbcSql sql;
  public final WorldEngine engine;
  public final List<Day> days = new ArrayList<>();
  public final List<String> userPosts = new ArrayList<>();
  public final List<String> userComments = new ArrayList<>();
  /** Every resident pair's meter at the start, to measure how dispositions moved. */
  public final Map<String, Double> startMeters = new TreeMap<>();
  public final long start;
  public long end;
  public String userId;
  private final WriterLane lane;
  private final ManualClock clock;
  private final ScriptWriter writer;
  private final LiveResponder responder;
  private final FakeWeather weather;
  private final FixtureHeadlines fixtures;
  private final RandomSource rng;
  private final List<Long> sessionStarts = new ArrayList<>();
  /** Invariant 9 (no spoilers), checked after every advance: anything aired ahead of committed time. */
  public final List<String> spoilers = new ArrayList<>();
  /** Comments to R he has already considered answering. */
  private final java.util.Set<String> answered = new java.util.HashSet<>();

  public Simulation(Options o) {
    this.options = o;
    this.lib = ClasspathContent.load(List.of(new ContentProperties.BlacklistEntry("pregnancy",
        List.of("pregnant", "pregnancy", "expecting a baby", "baby bump", "trimester", "baby shower", "gender reveal"))));
    this.start = START.atTime(3, 0).atZone(ZONE).toInstant().toEpochMilli();
    this.clock = new ManualClock(start);
    this.lane = new WriterLane("simulate-writer");
    var holder = new LeaseHolder();
    try {
      Files.createDirectories(o.dir());
      Files.deleteIfExists(o.dir().resolve("simulate.db"));
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    this.sql = JdbcSql.open(o.dir().resolve("simulate.db"), 2, lane, holder);
    MigrationRunner.fromClasspath(sql, sql, clock).migrate();
    sql.execute(new UnitOfWork().add("INSERT INTO world (id, name, network_name, timezone, seed, committed_until,"
        + " scripted_until, settings_json, created_at) VALUES (?, 'Simulation', 'Radiance', ?, ?, ?, ?, '{}', ?)",
        Schema.WORLD_ID, ZONE.getId(), o.seed(), start, start, start));
    var lease = new WriterLease(sql, sql, holder, clock, "simulate", Duration.ofDays(3650));
    if (!lease.tryAcquire()) throw new IllegalStateException("could not take the writer lease");
    this.engine = new WorldEngine(sql, lane, lib, o.knobs());
    this.writer = new ScriptWriter(sql, lane, engine, o.audit(), clock);
    this.responder = new LiveResponder(lib, o.knobs());
    this.weather = new FakeWeather(o.seed(), ZONE);
    this.fixtures = new FixtureHeadlines(o.seed(), ZONE, 100);
    this.rng = RandomSource.seeded(o.seed()).derive("simulate:" + o.persona());
  }

  /** Genesis, R's onboarding, then {@code days} world-days of nightly scripts and R's sessions. */
  public Simulation run() {
    var seed = SkeletonGenerator.generate(lib, new SkeletonGenerator.Settings(options.seed(), 3, 5, 4, START));
    new SeedImporter(sql, lane, lib).importSeed(seed, start);
    onboard();
    engine.reload();
    userId = engine.world().userId;
    engine.world().edges.values().forEach(e -> {
      if (!e.from.equals(userId) && !e.to.equals(userId)) startMeters.put(e.from + "|" + e.to, e.affinity);
    });
    for (int d = 0; d < options.days(); d++) {
      long batchAt = start + d * DAY;
      batch(batchAt);
      for (long s : sessions(batchAt)) session(s);
    }
    end = start + options.days() * DAY;
    engine.advanceTo(end);
    return this;
  }

  private void onboard() {
    var f = options.persona().features;
    var fakes = new FakeResponses();
    fakes.register(Onboarding.ProfileAppraisal.class, call -> new Onboarding.ProfileAppraisal(f.get("humor"),
        f.get("warmth"), f.get("intellect"), f.get("pretentiousness"), f.get("bragging"), f.get("vulnerability"),
        f.get("positivity"), f.get("edginess"), f.get("preachiness"), f.get("effort"), 0, 0, 0.1,
        List.of("tech", "sports")));
    var gateway = OfflineGateway.create(fakes, clock);
    new Onboarding(sql, lane, gateway, lib, options.knobs().crushPropensity()).onboard(new Onboarding.Profile(
        "Rob Marlow", "Software engineer. Logan Square. Softball, bad puns, good coffee.", "Northpoint Labs",
        "Chicago (Logan Square)", List.of("softball", "uiuc"), List.of("tech", "sports", "food"),
        Onboarding.Backstory.LOCAL), options.seed(), start);
  }

  /** The nightly batch at 03:00: refresh weather and headlines, air what's due, write the next script. */
  private void batch(long batchAt) {
    clock.set(batchAt);
    var aired = engine.advanceTo(batchAt);
    checkSpoilers();
    var hours = weather.hours(batchAt - batchAt % HOUR - 8 * DAY, batchAt - batchAt % HOUR + 3 * DAY);
    var uow = new UnitOfWork();
    new WeatherRepository(sql).save(uow, hours);
    var today = engine.world().date(batchAt);
    var headlines = new ArrayList<Headline>();
    for (int i = -1; i <= 0; i++) {
      headlines.addAll(HeadlineSelection.select(fixtures.on(today.plusDays(i)), options.knobs().headlinesPerDay(),
          options.knobs().regionMix()));
    }
    new HeadlineRepository(sql).save(uow, headlines);
    lane.run(() -> sql.commit(uow));
    var usable = headlines.stream().filter(h -> h.publishedAt() <= batchAt).toList();
    var book = new WeatherBook(ZONE, lib.weather()).putAll(hours);
    long scripted = sql.queryOne("SELECT scripted_until AS s FROM world", List.of(), r -> r.lng("s")).orElse(batchAt);
    long from = Math.max(scripted, batchAt);
    long to = batchAt + DAY + (long) (options.knobs().scriptSlackHours() * HOUR);
    var fidelity = Fidelities.forIdle(engine.world().lastSessionAt, batchAt, options.knobs());
    var result = writer.write(from, to, fidelity == null ? Fidelity.SUMMARY : fidelity, usable, book, primeHours(batchAt),
        ContentOrigin.SCRIPT);
    days.add(new Day(today, fidelity, result.planHash(), result.posts() + result.comments() + result.reactions()
        + result.events() + result.dms(), aired.aired(), aired.dropped(), result.rowsWritten() + aired.rowsWritten(),
        result.findings(), aired.dropReasons()));
    options.afterBatch().accept(this);
  }

  /** The clock jobs and leases run on in the harness (world time doubles as wall time here). */
  public ManualClock clock() {
    return clock;
  }

  /** R's usual online hours over the last 14 days (SPEC §5.1 prime time). */
  private java.util.Set<Integer> primeHours(long now) {
    var out = new TreeSet<Integer>();
    for (long s : sessionStarts) {
      if (s > now - 14 * DAY) out.add(java.time.Instant.ofEpochMilli(s).atZone(ZONE).getHour());
    }
    return out;
  }

  private List<Long> sessions(long batchAt) {
    long midnight = engine.world().date(batchAt).atStartOfDay(ZONE).toInstant().toEpochMilli();
    var hours = options.persona() == Persona.QUIET ? List.of(20.5) : List.of(8.2, 12.6, 20.4);
    var out = new ArrayList<Long>();
    for (double h : hours) out.add(midnight + (long) (h * HOUR) + rng.nextInt(40) * MIN);
    return out;
  }

  private void session(long t) {
    clock.set(t);
    engine.advanceTo(t);
    var w = engine.world();
    w.lastSessionAt = t;
    sessionStarts.add(t);
    lane.run(() -> sql.commit(new UnitOfWork().add("INSERT INTO session_log (id, started_at, ended_at) VALUES (?, ?, ?)",
        Ulid.next(t, rng.derive("session:" + t)), t, t + 20 * MIN)));
    var p = options.persona();
    int sessionsPerDay = p == Persona.QUIET ? 1 : 3;
    long now = t + MIN;
    String day = w.day(t);
    int postsToday = (int) userPosts.stream().filter(id -> w.items.containsKey(id) && w.day(w.items.get(id).time).equals(day))
        .count();
    if (rng.chance(p.postsPerDay / sessionsPerDay)) {
      now = post(now, postsToday);
    }
    int comments = Math.round(p.commentsPerDay / (float) sessionsPerDay);
    var feed = w.items.values().stream().filter(i -> i.type == Item.Type.POST && i.aired() && !i.authorId.equals(userId)
        && i.time > t - DAY && !i.commenters.contains(userId)).filter(i -> {
          var e = w.edgeOrNull(userId, i.authorId);
          return e != null && e.connected;
        }).toList();
    var picks = new ArrayList<>(feed);
    for (int i = 0; i < comments && !picks.isEmpty(); i++) {
      now = comment(now + 2 * MIN, picks.remove(rng.nextInt(picks.size())), null);
    }
    // answer comments on his posts since his last session
    // he answers some top-level comments on his own posts; he doesn't chase every reply
    var replies = w.items.values().stream().filter(i -> i.type == Item.Type.COMMENT && i.aired() && i.parentId == null
        && userId.equals(i.toward) && !i.authorId.equals(userId) && i.time <= t && answered.add(i.id)).toList();
    for (var c : replies) {
      if (rng.chance(p.replyRate)) now = comment(now + MIN, w.items.get(c.postId), c);
    }
    engine.advanceTo(now + MIN);
    checkSpoilers();
  }

  /** Nothing scripted is visible, or canon, before it airs (SPEC §5.5 invariant 9). */
  private void checkSpoilers() {
    long t = engine.world().time;
    for (var q : List.of("SELECT COUNT(*) AS n FROM post WHERE air_status = 'AIRED' AND created_at > ?",
        "SELECT COUNT(*) AS n FROM comment WHERE air_status = 'AIRED' AND created_at > ?",
        "SELECT COUNT(*) AS n FROM reaction WHERE air_status = 'AIRED' AND created_at > ?",
        "SELECT COUNT(*) AS n FROM event WHERE air_status = 'AIRED' AND world_time > ?",
        "SELECT COUNT(*) AS n FROM dm_message WHERE air_status = 'AIRED' AND created_at > ?",
        "SELECT COUNT(*) AS n FROM arc WHERE phase_entered_at > ?",
        "SELECT COUNT(*) AS n FROM memory WHERE world_time > ?")) {
      long n = sql.queryOne(q, List.of(t), r -> r.lng("n")).orElse(0L);
      if (n > 0) spoilers.add(n + " rows ahead of committed time " + t + ": " + q);
    }
    for (var a : engine.world().arcs.values()) {
      if (a.phaseEnteredAt > t) spoilers.add("committed arc " + a.id + " entered " + a.phase + " in the future");
    }
  }

  private long post(long t, int postsToday) {
    var p = options.persona();
    boolean photo = rng.chance(p.photoShare);
    var brief = new Brief(null, null, "R's own post", null, null, null, List.of("tech", "sports", "food"),
        p.features.get("edginess") * 0.6, p.features.get("edginess") * 0.4,
        Plan.extra("valence", p.features.get("positivity") - 0.4));
    String id = Ulid.next(t, rng.derive("r-post:" + t));
    var row = new PostRow(id, userId, t, photo ? PostRow.Kind.PHOTO : PostRow.Kind.STATUS, PostRow.Audience.FRIENDS,
        ContentOrigin.USER, AirStatus.SCRIPTED, "(R's post)", RenderStatus.RENDERED, brief.json(),
        app.radiance.kernel.Json.write(p.features), null, null, null, null, 3, Fidelity.FULL, "{}", 0);
    lane.run(() -> sql.commit(new SocialRepository(sql).savePosts(new UnitOfWork(), List.of(row))));
    engine.advanceTo(t);
    userPosts.add(id);
    var item = engine.world().items.get(id);
    commit(responder.onPost(engine.world(), item, p.features, postsToday + 1, t));
    return t;
  }

  /** R comments on {@code post}, or replies to {@code parent} on it. */
  private long comment(long t, Item post, Item parent) {
    if (post == null) return t;
    var p = options.persona();
    var stance = pick(p.stances);
    String toward = parent != null ? parent.authorId : post.authorId;
    var brief = new Brief(post.defId, post.category, null, stance.name(), toward, Airing.interactionFor(stance.name()),
        post.topics, post.controversy, post.drama, Plan.extra("on", post.id));
    String id = Ulid.next(t, rng.derive("r-comment:" + t + ":" + post.id));
    var row = new CommentRow(id, post.id, parent == null ? null : parent.id, userId, t, ContentOrigin.USER,
        AirStatus.SCRIPTED, "(R's comment)", null, RenderStatus.RENDERED, brief.json(), null, "{}");
    lane.run(() -> sql.commit(new SocialRepository(sql).saveComments(new UnitOfWork(), List.of(row))));
    engine.advanceTo(t);
    var item = engine.world().items.get(id);
    if (item == null) return t;
    userComments.add(id);
    commit(responder.onComment(engine.world(), item, p.features, t));
    return t;
  }

  private Stance pick(Map<Stance, Double> weights) {
    double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
    double u = rng.nextDouble() * total;
    for (var e : new TreeMap<>(weights).entrySet()) {
      u -= e.getValue();
      if (u < 0) return e.getKey();
    }
    return Stance.SUPPORT;
  }

  private void commit(Script s) {
    if (s.size() == 0) return;
    var uow = new UnitOfWork();
    var social = new SocialRepository(sql);
    social.saveComments(uow, s.comments);
    social.saveReactions(uow, s.reactions);
    lane.run(() -> sql.commit(uow));
  }

  /** The writer lane, for tools that write to the simulated world (rendering, the bake-off). */
  public WriterLane sqlLane() {
    return lane;
  }

  @Override
  public void close() {
    lane.close();
    sql.close();
  }
}
