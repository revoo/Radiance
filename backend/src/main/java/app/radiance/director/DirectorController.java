package app.radiance.director;

import app.radiance.batch.NightlyBatch;
import app.radiance.kernel.Json;
import app.radiance.live.LivePlay;
import app.radiance.persistence.Sql;
import app.radiance.sim.Incidents;
import app.radiance.sim.WorldEngine;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** The Director's console (SPEC §5.16): looking under the hood, and pulling the levers. */
@ConditionalOnWebApplication
@RestController
@RequestMapping("/api/director")
class DirectorController {

  /** What to fire: an event def, an arc, or a breakup between two residents. */
  record Injection(String kind, String defId, String actor, String other) {}

  /** What firing it scripted. */
  record Injected(int events, int posts, int comments, int reactions, Long firstAt) {}

  private static final int MAX_FAST_FORWARD_HOURS = 24 * 14;

  private final DirectorReads reads;
  private final DirectorSettings settings;
  private final LivePlay live;
  private final NightlyBatch batch;
  private final Incidents incidents;

  DirectorController(Sql sql, WorldEngine engine, DirectorSettings settings, LivePlay live, NightlyBatch batch, app.radiance.llm.LlmConsole llm,
      app.radiance.kernel.WallClock clock) {
    this.incidents = new Incidents(engine);
    this.reads = new DirectorReads(sql, engine, llm, incidents, clock);
    this.settings = settings;
    this.live = live;
    this.batch = batch;
  }

  private static ResponseStatusException bad(String detail) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
  }

  private static ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + " not found");
  }

  // ---------------------------------------------------------------------------------------- looking

  @GetMapping("/arcs")
  List<DirectorReads.ArcView> arcs() {
    return reads.arcs();
  }

  @GetMapping("/arcs/{id}/events")
  List<DirectorReads.LogEntry> arcEvents(@PathVariable String id) {
    return reads.arcEvents(id);
  }

  @GetMapping("/graph")
  DirectorReads.Graph graph() {
    return reads.graph();
  }

  @GetMapping("/graph/edge")
  DirectorReads.EdgeHistory edge(@RequestParam String from, @RequestParam String to) {
    return reads.edge(from, to);
  }

  @GetMapping("/residents/{handle}")
  DirectorReads.Inspector inspector(@PathVariable String handle) {
    return reads.inspector(handle, live.now()).orElseThrow(() -> notFound("resident"));
  }

  @GetMapping("/events")
  List<DirectorReads.LogEntry> events(@RequestParam(required = false) String source, @RequestParam(required = false) String status,
      @RequestParam(required = false) String actor, @RequestParam(required = false) String q, @RequestParam(required = false) Long before,
      @RequestParam(defaultValue = "60") int limit) {
    return reads.events(source, status, actor, q, before, Math.max(1, Math.min(200, limit)));
  }

  @GetMapping("/reports")
  DirectorReads.Reports reports() {
    return reads.reports();
  }

  @GetMapping("/batch")
  DirectorReads.BatchView batch() {
    return reads.batch(live.now(), batch.isRunning());
  }

  @PostMapping("/batch/run")
  ResponseEntity<Void> runBatch() {
    if (batch.isRunning()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A batch is already running");
    Thread.ofVirtual().name("director-batch").start(() -> batch.run(NightlyBatch.Kind.MANUAL));
    return ResponseEntity.accepted().build();
  }

  @GetMapping("/costs")
  DirectorReads.Costs costs() {
    return reads.costs();
  }

  @GetMapping("/prompts")
  List<DirectorReads.PromptRow> prompts(@RequestParam(required = false) String purpose, @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "50") int limit) {
    return reads.prompts(purpose, q, Math.max(1, Math.min(200, limit)));
  }

  @GetMapping("/prompts/{id}")
  JsonNode prompt(@PathVariable String id) {
    return reads.prompt(id).map(Json::tree).orElseThrow(() -> notFound("prompt"));
  }

  // ----------------------------------------------------------------------------------------- settings

  @GetMapping("/settings")
  DirectorSettings.Current settings() {
    return settings.current();
  }

  @PutMapping("/settings")
  DirectorSettings.Current update(@RequestBody JsonNode patch) {
    try {
      settings.update(patch);
    } catch (DirectorSettings.Invalid e) {
      throw bad(e.getMessage());
    }
    return settings.current();
  }

  // --------------------------------------------------------------------------------------------- time

  @GetMapping("/time")
  DirectorReads.Time time() {
    return reads.time(live.now(), live.status().backfilling());
  }

  @PostMapping("/time/pause")
  DirectorReads.Time pause() {
    live.pause();
    return time();
  }

  @PostMapping("/time/resume")
  DirectorReads.Time resume() {
    live.resume();
    return time();
  }

  @PostMapping("/time/fast-forward")
  DirectorReads.Time fastForward(@RequestParam int hours) {
    if (hours < 1 || hours > MAX_FAST_FORWARD_HOURS) throw bad("Fast-forward between 1 and " + MAX_FAST_FORWARD_HOURS + " hours");
    live.fastForward(hours);
    return time();
  }

  // ------------------------------------------------------------------------------------------ incidents

  @GetMapping("/injectables")
  DirectorReads.Injectables injectables() {
    return reads.injectables();
  }

  @PostMapping("/inject")
  ResponseEntity<Injected> inject(@RequestBody Injection in) {
    long now = live.now();
    try {
      var script = switch (in.kind() == null ? "" : in.kind()) {
        case "EVENT" -> incidents.event(in.defId(), in.actor(), in.other(), now);
        case "ARC" -> incidents.arc(in.defId(), in.actor(), in.other(), now);
        case "BREAKUP" -> incidents.breakup(in.actor(), in.other(), now);
        default -> throw bad("Fire an EVENT, an ARC or a BREAKUP");
      };
      if (script.size() == 0) throw bad("That didn't lead to anything");
      live.injected(script);
      Long first = script.entries().stream().map(e -> e.time()).min(Long::compare).orElse(null);
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(new Injected(script.events.size(), script.posts.size(), script.comments.size(), script.reactions.size(), first));
    } catch (Incidents.Refused e) {
      throw bad(e.getMessage());
    }
  }
}
