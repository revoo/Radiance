package app.radiance.world.genesis;

import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.kernel.WallClock;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.LlmGateway;
import app.radiance.persistence.LeaseHolder;
import app.radiance.persistence.Sql;
import app.radiance.persistence.WriterLane;
import app.radiance.world.WorldProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Genesis as the app runs it: skeleton → flesh-out → {@code data/world-seed.json} → R reviews and edits
 * it → import (SPEC §6.4). Used by the {@code genesis} command and the Director's console.
 */
@Service
public class GenesisService {

  public record Summary(String file, long seed, int residents, int fleshedOut, int relationships, int arcs,
      int entities) {}

  private final ContentLibrary lib;
  private final ContentProperties content;
  private final WorldProperties world;
  private final LlmGateway gateway;
  private final Sql sql;
  private final WriterLane lane;
  private final LeaseHolder lease;
  private final WallClock clock;
  private final Path seedFile;
  private final int grouches;
  private final int sweethearts;
  private final java.time.Duration history;
  private final org.springframework.context.ApplicationEventPublisher events;

  public GenesisService(ContentLibrary lib, ContentProperties content, WorldProperties world,
      LlmGateway gateway, FakeResponses fakes, Sql sql, WriterLane lane, LeaseHolder lease, WallClock clock,
      @Value("${radiance.genesis.seed-file:data/world-seed.json}") String seedFile,
      @Value("${radiance.social.temperament.grouches:3}") int grouches,
      @Value("${radiance.social.temperament.sweethearts:5}") int sweethearts,
      @Value("${radiance.batch.genesis-history:14d}") String history,
      org.springframework.context.ApplicationEventPublisher events) {
    this.lib = lib;
    this.content = content;
    this.world = world;
    this.gateway = gateway;
    this.sql = sql;
    this.lane = lane;
    this.lease = lease;
    this.clock = clock;
    this.seedFile = Path.of(seedFile);
    this.grouches = grouches;
    this.sweethearts = sweethearts;
    this.history = app.radiance.content.Durations.parse(history);
    this.events = events;
    GenesisFakes.register(fakes);
  }

  public Path seedFile() {
    return seedFile;
  }

  /** The deterministic skeleton for {@code seed}, dated today in the world's time zone. */
  public WorldSeed skeleton(long seed) {
    var today = LocalDate.ofInstant(Instant.ofEpochMilli(clock.nowMs()), ZoneId.of(world.timezone()));
    return SkeletonGenerator.generate(lib, new SkeletonGenerator.Settings(seed, grouches, sweethearts,
        content.strongPrejudice(), today));
  }

  /** Skeleton plus prose from the {@code genesis} model route (see {@link #preview(long, boolean, boolean)}). */
  public Summary preview(long seed, boolean skeletonOnly) {
    return preview(seed, skeletonOnly, false);
  }

  /**
   * Skeleton plus prose, checkpointed to the seed file after every batch; resumes a partial file. With
   * {@code authored}, the prose comes from {@code content/genesis/seed-<seed>/} (ADR 0017) instead of
   * the model route, through the same checks.
   */
  public Summary preview(long seed, boolean skeletonOnly, boolean authored) {
    var source = authored ? authoredSource(seed) : FleshOut.Source.gateway(gateway);
    WorldSeed current;
    if (Files.exists(seedFile)) {
      var existing = SeedFiles.read(seedFile);
      current = existing.seed() == seed ? existing : skeleton(seed);
    } else {
      current = skeleton(seed);
    }
    SeedFiles.write(seedFile, current);
    if (!skeletonOnly) {
      current = new FleshOut(source, lib, world.networkName()).run(current, s -> SeedFiles.write(seedFile, s));
      SeedFiles.write(seedFile, current);
    }
    return summary(current);
  }

  /** The prose authored in-session for {@code seed}, or an error if there is none. */
  public static FleshOut.Source authoredSource(long seed) {
    String dir = "content/genesis/seed-" + seed + "/";
    var files = app.radiance.content.ClasspathContent.files(dir, "json");
    if (files.isEmpty()) throw new FleshOut.GenesisException("no authored prose in " + dir);
    return FleshOut.Source.authored(files, "authored in-session by Opus (" + dir + ")");
  }

  /** Rewrites one resident's prose in the seed file; their identity stays (SPEC §6.4). */
  public Summary regenerate(String handle) {
    var updated = new FleshOut(gateway, lib, world.networkName()).regenerate(SeedFiles.read(seedFile), handle);
    SeedFiles.write(seedFile, updated);
    return summary(updated);
  }

  /** Imports the (possibly hand-edited) seed file. Needs the writer lease. */
  public SeedImporter.Result commit() throws InterruptedException {
    for (int i = 0; i < 600 && !lease.isHeld(); i++) Thread.sleep(100);
    if (!lease.isHeld()) {
      throw new IllegalStateException("couldn't get the writer lease; is another process writing the world?");
    }
    // the world starts genesis-history back: the first batch backfills those days, then writes the
    // first script (SPEC §6.4 steps 5 and 8)
    var seed = SeedFiles.read(seedFile);
    var result = new SeedImporter(sql, lane, lib).importSeed(seed, clock.nowMs() - history.toMillis());
    events.publishEvent(new app.radiance.world.GenesisImported(seed.seed(), result.residents()));
    return result;
  }

  public Summary summary(WorldSeed s) {
    int fleshed = (int) s.residents().stream().filter(r -> r.bio() != null).count();
    return new Summary(seedFile.toString(), s.seed(), s.residents().size(), fleshed, s.relationships().size(),
        s.arcs().size(), s.entities().size());
  }
}
