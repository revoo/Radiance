package app.radiance.director;

import app.radiance.world.genesis.GenesisService;
import app.radiance.world.genesis.SeedImporter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Genesis from the Director's console (SPEC §6.4, §7). Works on {@code data/world-seed.json}. */
@RestController
@RequestMapping("/api/director")
class GenesisController {

  private final GenesisService genesis;

  GenesisController(GenesisService genesis) {
    this.genesis = genesis;
  }

  /** Writes (or resumes) the seed file: skeleton, then flesh-out unless {@code skeletonOnly}. */
  @PostMapping("/genesis/preview")
  GenesisService.Summary preview(@RequestParam long seed,
      @RequestParam(defaultValue = "false") boolean skeletonOnly,
      @RequestParam(defaultValue = "false") boolean authored) {
    return genesis.preview(seed, skeletonOnly, authored);
  }

  /** Imports the seed file, edited or not. */
  @PostMapping("/genesis/commit")
  SeedImporter.Result commit() throws InterruptedException {
    return genesis.commit();
  }

  /** Before import, residents are addressed by handle; their roster identity is kept. */
  @PostMapping("/actors/{handle}/regenerate")
  GenesisService.Summary regenerate(@PathVariable String handle) {
    return genesis.regenerate(handle);
  }
}
