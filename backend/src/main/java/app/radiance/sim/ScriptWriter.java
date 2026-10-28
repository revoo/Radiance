package app.radiance.sim;

import app.radiance.content.ContentLibrary;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.Json;
import app.radiance.messenger.DmRepository;
import app.radiance.news.Headline;
import app.radiance.persistence.Schema;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.model.Intent;
import app.radiance.social.SocialRepository;
import app.radiance.story.EventRepository;
import app.radiance.weather.WeatherBook;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Writes a script (SPEC §5.1, step 5): copies the committed world, applies the not-yet-aired rest of
 * the current script, calibrates the event mix for the coming week, then plans slices from
 * {@code from} to {@code to}, committing each slice's rows as one unit of work and moving
 * {@code world.scripted_until}. Returns a plan hash over everything planned, so tests can prove the
 * same seed and inputs give the same script.
 */
public final class ScriptWriter {

  /** What a run planned. */
  public record Result(String planHash, int slices, int events, int posts, int comments, int reactions, int dms,
      int intentsLeft, List<String> findings, long rowsWritten) {}

  private final Sql sql;
  private final WriterLane lane;
  private final WorldEngine engine;
  private final ContinuityAuditor.Mode auditMode;

  public ScriptWriter(Sql sql, WriterLane lane, WorldEngine engine, ContinuityAuditor.Mode auditMode) {
    this.sql = sql;
    this.lane = lane;
    this.engine = engine;
    this.auditMode = auditMode;
  }

  /** The nightly path: slices of one fidelity from {@code from} to {@code to}. */
  public Result write(long from, long to, Fidelity fidelity, List<Headline> headlines, WeatherBook weather,
      Set<Integer> primeHours, ContentOrigin origin) {
    return write(Fidelities.slices(from, to, fidelity), headlines, weather, primeHours, origin);
  }

  public Result write(List<Plan.Slice> slices, List<Headline> headlines, WeatherBook weather, Set<Integer> primeHours,
      ContentOrigin origin) {
    ContentLibrary lib = engine.lib();
    Knobs k = engine.knobs();
    var committed = engine.world();
    var copy = committed.copy();
    var noCheck = new Airing.Ctx(lib, k, weather, false, Airing.Lookup.NONE);
    Airing.air(copy, pending().entries(), noCheck);
    if (slices.isEmpty()) return new Result(hash(sha()), 0, 0, 0, 0, 0, 0, copy.intents.size(), List.of(), 0);
    copy.time = Math.max(copy.time, slices.getFirst().start());

    var raw = Planner.rawRates(copy, lib, copy.date(slices.getFirst().start()), 7, headlines);
    var rates = new TreeMap<String, Double>();
    copy.residents().forEach(r -> rates.put(r.id, r.routine.postsPerDay()));
    var scales = new TreeMap<>(Rates.calibrate(raw, rates, k.eventMix(), fixedPerDay(slices.getFirst().start(),
        committed.userId)));
    // LIFE and DRAMA also ride the tension factor; divide by its expected weekly mean so the week still
    // lands on the mix while drama clusters near the peaks (SPEC §5.4)
    double mean = Storyteller.meanFactor(k.profile(), copy.seed, slices.getFirst().start(), copy.tension, k.dramaIntensity());
    for (String c : java.util.List.of("LIFE", "DRAMA")) scales.computeIfPresent(c, (x, v) -> v / mean);
    var inputs = new Plan.Inputs(headlines, weather, primeHours, scales, origin);

    var planner = new Planner(lib, k);
    var auditor = new ContinuityAuditor(lib, auditMode);
    var digest = sha();
    int events = 0, posts = 0, comments = 0, reactions = 0, dms = 0;
    long rows = 0;
    for (var slice : slices) {
      var script = planner.plan(copy, slice, inputs);
      auditor.beforeApply(copy, script);
      Airing.air(copy, script.entries(), noCheck);
      copy.time = slice.end();
      auditor.afterApply(copy, slice.end());
      digest.update(canonical(script).getBytes(StandardCharsets.UTF_8));
      var uow = new UnitOfWork();
      new EventRepository(sql).save(uow, script.events);
      var social = new SocialRepository(sql);
      social.savePosts(uow, script.posts);
      social.saveComments(uow, script.comments);
      social.saveReactions(uow, script.reactions);
      var dmRepo = new DmRepository(sql);
      dmRepo.saveThreads(uow, script.threads);
      dmRepo.saveMessages(uow, script.dms);
      uow.add("UPDATE world SET scripted_until = ? WHERE id = ? AND scripted_until < ?", slice.end(), Schema.WORLD_ID,
          slice.end());
      rows += script.size() + script.threads.size() + 1;
      lane.run(() -> sql.commit(uow));
      events += script.events.size();
      posts += script.posts.size();
      comments += script.comments.size();
      reactions += script.reactions.size();
      dms += script.dms.size();
    }

    // Intents past the end wait in scheduled_intent; stored ones that fired are done.
    long end = slices.getLast().end();
    var stored = committed.intents.stream().map(Intent::id).collect(Collectors.toSet());
    var firedIds = new HashSet<String>();
    planner.fired.forEach(i -> firedIds.add(i.id()));
    var uow = new UnitOfWork();
    for (String id : firedIds) {
      if (stored.contains(id)) uow.add("UPDATE scheduled_intent SET status = 'DONE' WHERE id = ?", id);
    }
    var left = copy.intents.stream().filter(i -> i.dueAt() >= end).toList();
    var rowsLeft = left.stream().map(i -> new ScheduledIntentRow(i.id(), i.dueAt(), i.actorId(), i.kind(), i.targetType(),
        i.targetId(), Json.write(new TreeMap<>(i.payload())), ScheduledIntentRow.Status.PENDING, i.causeEventId())).toList();
    new ScheduledIntentRepository(sql).save(uow, rowsLeft);
    if (!uow.isEmpty()) lane.run(() -> sql.commit(uow));
    committed.intents.removeIf(i -> firedIds.contains(i.id()));
    for (var i : left) if (!stored.contains(i.id())) committed.intents.add(i);
    return new Result(hash(digest), slices.size(), events, posts, comments, reactions, dms, left.size(),
        List.copyOf(auditor.findings), rows + rowsLeft.size());
  }

  /**
   * Posts per day the mix can't scale, by category, from the last week that aired: arc posts and
   * calendar posts (SPEC §5.4: they count toward their category but are never scaled down).
   */
  private java.util.Map<String, Double> fixedPerDay(long now, String userId) {
    long from = now - 7 * 86_400_000L;
    var rows = sql.query("SELECT p.brief_json AS b, p.created_at AS t FROM post p LEFT JOIN event e ON e.id = p.event_id"
        + " WHERE p.created_at >= ? AND p.created_at < ? AND p.air_status = 'AIRED' AND p.author_id <> ?"
        + " AND (p.arc_id IS NOT NULL OR e.source = 'CALENDAR')", List.of(from, now, userId == null ? "" : userId),
        r -> Brief.parse(r.str("b")).category());
    var first = sql.queryOne("SELECT MIN(created_at) AS t FROM post", List.of(), r -> r.isNull("t") ? now : r.lng("t"))
        .orElse(now);
    double days = Math.max(1, Math.min(7, (now - first) / 86_400_000.0));
    var out = new TreeMap<String, Double>();
    for (String c : rows) if (c != null) out.merge(c, 1 / days, Double::sum);
    return out;
  }

  /** Everything scripted that hasn't aired yet, to apply to the planning copy first. */
  private Script pending() {
    var s = new Script();
    s.events.addAll(new EventRepository(sql).scripted());
    var social = new SocialRepository(sql);
    s.posts.addAll(social.scriptedPosts());
    s.comments.addAll(social.scriptedComments());
    s.reactions.addAll(social.scriptedReactions());
    s.dms.addAll(new DmRepository(sql).scripted());
    return s;
  }

  static String canonical(Script s) {
    var sb = new StringBuilder();
    for (var e : s.entries()) {
      Object row = switch (e.type()) {
        case EVENT -> e.event();
        case POST -> e.post();
        case COMMENT -> e.comment();
        case REACTION -> e.reaction();
        case DM -> e.dm();
      };
      sb.append(Json.write(row)).append('\n');
    }
    return sb.toString();
  }

  private static MessageDigest sha() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hash(MessageDigest d) {
    return HexFormat.of().formatHex(d.digest()).substring(0, 16);
  }
}
