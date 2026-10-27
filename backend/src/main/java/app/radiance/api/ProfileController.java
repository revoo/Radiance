package app.radiance.api;

import app.radiance.cast.ActorRow;
import app.radiance.cast.CastRepository;
import app.radiance.graph.Onboarding;
import app.radiance.kernel.WallClock;
import app.radiance.persistence.Sql;
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
 * R's profile and onboarding (SPEC §7). The first {@code PUT} onboards R; editing an existing profile
 * arrives with the profile page in M7. The SPA's onboarding flow is M7 as well.
 */
@RestController
@RequestMapping("/api/profile")
class ProfileController {

  record ProfileView(String id, String handle, String displayName, String bio, String employer,
      String livesIn, String occupation, String birthDate) {}

  private final Sql sql;
  private final Onboarding onboarding;
  private final WallClock clock;

  ProfileController(Sql sql, Onboarding onboarding, WallClock clock) {
    this.sql = sql;
    this.onboarding = onboarding;
    this.clock = clock;
  }

  @GetMapping
  ResponseEntity<ProfileView> get() {
    return user().map(a -> ResponseEntity.ok(new ProfileView(a.id(), a.handle(), a.displayName(), a.bio(),
            a.employer(), a.currentCity(), a.occupation(), a.birthDate())))
        .orElse(ResponseEntity.notFound().build());
  }

  @PutMapping
  ResponseEntity<Onboarding.Result> put(@RequestBody Onboarding.Profile profile) {
    if (user().isPresent()) return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    long seed = new WorldRepository(sql).load().orElseThrow().seed();
    return ResponseEntity.ok(onboarding.onboard(normalize(profile), seed, clock.nowMs()));
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
