package app.radiance.api;

import app.radiance.cast.ActorRow;
import app.radiance.cast.CastRepository;
import app.radiance.graph.Onboarding;
import app.radiance.kernel.WallClock;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.WorldEngine;
import app.radiance.world.WorldRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * R's profile and onboarding (SPEC §7). The first {@code PUT} onboards R; later ones edit what he can
 * change about himself — name, bio, employer and where he lives.
 */
@RestController
@RequestMapping("/api/profile")
class ProfileController {

  record ProfileView(String id, String handle, String displayName, String bio, String employer,
      String livesIn, String occupation, String birthDate) {}

  private final Sql sql;
  private final WriterLane lane;
  private final Onboarding onboarding;
  private final WorldEngine engine;
  private final WallClock clock;

  ProfileController(Sql sql, WriterLane lane, Onboarding onboarding, WorldEngine engine, WallClock clock) {
    this.sql = sql;
    this.lane = lane;
    this.onboarding = onboarding;
    this.engine = engine;
    this.clock = clock;
  }

  @GetMapping
  ResponseEntity<ProfileView> get() {
    return user().map(a -> ResponseEntity.ok(new ProfileView(a.id(), a.handle(), a.displayName(), a.bio(),
            a.employer(), a.currentCity(), a.occupation(), a.birthDate())))
        .orElse(ResponseEntity.notFound().build());
  }

  @PutMapping
  ResponseEntity<?> put(@RequestBody Onboarding.Profile profile) {
    var existing = user();
    if (existing.isPresent()) {
      var a = existing.get();
      String name = blank(profile.name()) ? a.displayName() : profile.name().strip();
      lane.run(() -> sql.commit(new UnitOfWork().add(
          "UPDATE actor SET display_name = ?, bio = ?, employer = ?, current_city = ? WHERE id = ?", name,
          profile.bio() == null ? a.bio() : profile.bio().strip(),
          profile.employer() == null ? a.employer() : profile.employer().strip(),
          profile.livesIn() == null ? a.currentCity() : profile.livesIn().strip(), a.id())));
      // the engine and the renderer's cast directory read names from the committed world
      if (!name.equals(a.displayName())) engine.reload();
      return get();
    }
    if (blank(profile.name())) return ResponseEntity.badRequest().build();
    long seed = new WorldRepository(sql).load().orElseThrow().seed();
    var result = onboarding.onboard(normalize(profile), seed, clock.nowMs());
    engine.reload(); // R is in the world now
    return ResponseEntity.ok(result);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static Onboarding.Profile normalize(Onboarding.Profile p) {
    return new Onboarding.Profile(p.name(), p.bio(), p.employer(), p.livesIn(),
        p.circles() == null ? List.of() : p.circles(), p.interests() == null ? List.of() : p.interests(),
        p.backstory());
  }

  private java.util.Optional<ActorRow> user() {
    return new CastRepository(sql).actors().stream().filter(a -> a.kind() == ActorRow.Kind.USER).findFirst();
  }
}
