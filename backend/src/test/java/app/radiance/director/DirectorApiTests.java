package app.radiance.director;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.radiance.batch.NightlyBatch;
import app.radiance.content.ContentLibrary;
import app.radiance.kernel.Json;
import app.radiance.live.LivePlay;
import app.radiance.llm.LlmConsole;
import app.radiance.llm.Purpose;
import app.radiance.persistence.Sql;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.Knobs;
import app.radiance.sim.WorldEngine;
import app.radiance.world.genesis.GenesisService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The console's API on a genesis world, through the real security chain (SPEC §5.16, §12 M13). */
@SpringBootTest(properties = "radiance.genesis.seed-file=${java.io.tmpdir}/radiance-test/director-seed.json")
@AutoConfigureMockMvc
@ActiveProfiles({"offline", "test"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DirectorApiTests {

  static final RequestPostProcessor R = user("dev");

  @Autowired MockMvc mvc;
  @Autowired GenesisService genesis;
  @Autowired Sql sql;
  @Autowired WriterLane lane;
  @Autowired WorldEngine engine;
  @Autowired ContentLibrary lib;
  @Autowired LlmConsole llm;
  @Autowired NightlyBatch batch;
  @Autowired LivePlay live;
  @Autowired ObjectProvider<LivePlay> lives;
  @Autowired DirectorSettings settings;
  @Autowired app.radiance.llm.DefaultLlmGateway gateway;

  @BeforeAll
  void world() throws Exception {
    genesis.preview(7, true, false);
    genesis.commit();
    mvc.perform(put("/api/profile").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
        {"name": "Sam Rivera", "bio": "Engineer, runner.", "employer": "Acme", "livesIn": "Logan Square",
         "circles": ["softball"], "interests": ["tech", "fitness"], "backstory": "LOCAL"}"""))
        .andExpect(status().isOk());
  }

  ResultActions put_(String json) throws Exception {
    return mvc.perform(put("/api/director/settings").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  tools.jackson.databind.JsonNode read(String url) throws Exception {
    return Json.tree(mvc.perform(get(url).with(R)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  @Test
  @Order(1)
  void everyPanelAnswers_AndNoneWithoutALogin() throws Exception {
    for (String url : List.of("arcs", "graph", "events", "reports", "batch", "costs", "settings", "time", "injectables", "prompts", "pacer")) {
      mvc.perform(get("/api/director/" + url).with(R)).andExpect(status().isOk());
      mvc.perform(get("/api/director/" + url)).andExpect(status().isUnauthorized());
    }
    mvc.perform(post("/api/director/time/pause").with(R)).andExpect(status().isForbidden());
    mvc.perform(post("/api/director/inject").with(R).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    mvc.perform(put("/api/director/settings").with(R).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
  }

  @Test
  @Order(2)
  void theGraphHasEveryoneAndTheInspectorOpensOneOfThem() throws Exception {
    var graph = read("/api/director/graph");
    assertThat(graph.get("nodes").size()).isEqualTo(sql.queryOne("SELECT COUNT(*) AS n FROM actor", List.of(), r -> r.lng("n")).orElseThrow().intValue());
    assertThat(graph.get("edges").size()).isPositive();
    var e = graph.get("edges").get(0);
    var history = read("/api/director/graph/edge?from=" + e.get("from").asString() + "&to=" + e.get("to").asString());
    assertThat(history.get("there").get("kind").asString()).isEqualTo(e.get("kind").asString());

    String handle = sql.queryOne("SELECT handle FROM actor WHERE kind = 'BOT' LIMIT 1", List.of(), r -> r.str("handle")).orElseThrow();
    var inspector = read("/api/director/residents/" + handle);
    assertThat(inspector.get("who").get("handle").asString()).isEqualTo(handle);
    assertThat(inspector.get("traits").isObject()).isTrue();
    mvc.perform(get("/api/director/residents/nobody").with(R)).andExpect(status().isNotFound());
    String me = sql.queryOne("SELECT handle FROM actor WHERE kind = 'USER'", List.of(), r -> r.str("handle")).orElseThrow();
    mvc.perform(get("/api/director/residents/" + me).with(R)).andExpect(status().isNotFound());
  }

  @Test
  @Order(3)
  void aBreakupThroughTheApi_ThenFastForward_AiresInTheFeed() throws Exception {
    var couples = read("/api/director/injectables").get("couples");
    assertThat(couples.size()).isPositive();
    String a = couples.get(0).get(0).get("id").asString(), b = couples.get(0).get(1).get("id").asString();
    var made = Json.tree(mvc.perform(post("/api/director/inject").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"BREAKUP\",\"actor\":\"" + a + "\",\"other\":\"" + b + "\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    assertThat(made.get("posts").asInt()).isPositive();
    assertThat(live.awaitIdle(Duration.ofSeconds(20))).isTrue();

    long before = read("/api/director/time").get("worldTime").asLong();
    var time = Json.tree(mvc.perform(post("/api/director/time/fast-forward?hours=6").with(R).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertThat(time.get("worldTime").asLong()).isGreaterThanOrEqualTo(before + 6 * 3_600_000L);
    assertThat(live.awaitIdle(Duration.ofSeconds(30))).isTrue();

    var log = read("/api/director/events?source=DIRECTOR");
    assertThat(log.size()).isPositive();
    assertThat(log.get(0).get("status").asString()).isEqualTo("AIRED");
    var feed = read("/api/feed");
    boolean found = false;
    for (var p : feed.get("items")) {
      String author = p.get("author").get("id").asString();
      if (author.equals(a) || author.equals(b)) {
        found |= p.get("reactions").get("total").asInt() > 0;
      }
    }
    assertThat(found).as("the breakup is in the feed, with people responding").isTrue();
    mvc.perform(post("/api/director/time/fast-forward?hours=0").with(R).with(csrf())).andExpect(status().isBadRequest());
    mvc.perform(post("/api/director/inject").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"kind\":\"BREAKUP\",\"actor\":\"" + a + "\",\"other\":\"" + b + "\"}")).andExpect(status().isBadRequest());
    mvc.perform(post("/api/director/inject").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"NOPE\"}")).andExpect(status().isBadRequest());
  }

  @Test
  @Order(4)
  void knobsTakeAtOnce_AreCheckedFirst_AndComeBackAfterARestart() throws Exception {
    put_("{\"knobs\":{\"dramaIntensity\":2.5,\"profile\":\"CHAOTIC\",\"eventMix\":{\"DAILY\":0.5,\"NEWS\":0.1,\"LIFE\":0.1,\"HOBBY\":0.1,\"FAMILY\":0.1,\"SOCIAL\":0.05,\"DRAMA\":0.05}}}")
        .andExpect(status().isOk()).andExpect(jsonPath("$.knobs.dramaIntensity").value(2.5));
    assertThat(engine.knobs().dramaIntensity()).isEqualTo(2.5);
    assertThat(engine.knobs().profile()).isEqualTo("CHAOTIC");
    assertThat(new java.util.ArrayList<>(engine.knobs().eventMix().keySet())).as("still in a fixed order").isSorted();

    double defaultTemptation = Knobs.defaults().temptation();
    for (String bad : List.of("{\"knobs\":{\"dramaIntensity\":99}}", "{\"knobs\":{\"nope\":1}}", "{\"knobs\":{\"profile\":\"WILD\"}}", "{\"knobs\":{\"maxDramaPerDay\":1.5}}",
        "{\"knobs\":{\"regionMix\":{\"LOCAL\":1}}}", "{\"knobs\":{\"regionMix\":{\"LOCAL\":0,\"NATIONAL\":0,\"WORLD\":0}}}", "{\"nope\":1}", "{\"imagesPerDay\":-1}", "{\"budgets\":{\"dailyUsd\":-1}}",
        "{\"routes\":{\"render-thread\":{\"model\":\"no-such-model\"}}}", "{\"blacklist\":[\"no_such_arc\"]}", "{\"pacing\":{\"responseCurve\":null}}",
        "{\"knobs\":{\"temptation\":2},\"imagesPerDay\":-1}")) {
      put_(bad).andExpect(status().isBadRequest());
    }
    assertThat(engine.knobs().temptation()).as("a change with one bad part changes nothing").isEqualTo(defaultTemptation);
    assertThat(engine.knobs().dramaIntensity()).isEqualTo(2.5);

    put_("{\"blacklist\":[\"new_hobby\"],\"budgets\":{\"dailyUsd\":3.5},\"imagesPerDay\":40}").andExpect(status().isOk());
    assertThat(lib.isBlacklisted("new_hobby")).isTrue();
    assertThat(llm.budgets().dailyUsd()).isEqualTo(3.5);
    assertThat(batch.imageLimits().photosPerDay()).isEqualTo(40);

    // a restart: the stored JSON is all that's left
    engine.tune(Knobs.defaults());
    lib.unblock("new_hobby");
    var again = new DirectorSettings(sql, lane, engine, llm, batch, lives);
    again.afterSingletonsInstantiated();
    assertThat(engine.knobs().dramaIntensity()).isEqualTo(2.5);
    assertThat(lib.isBlacklisted("new_hobby")).isTrue();
    assertThat(llm.budgets().dailyUsd()).isEqualTo(3.5);

    // null puts a section back
    put_("{\"knobs\":null,\"blacklist\":null,\"budgets\":null,\"imagesPerDay\":null}").andExpect(status().isOk());
    assertThat(lib.isBlacklisted("new_hobby")).isFalse();
    assertThat(engine.knobs().dramaIntensity()).isEqualTo(Knobs.defaults().dramaIntensity());
  }

  @Test
  @Order(5)
  void aRouteChangeTakesAtOnce_AndTheLockedBlacklistStays() throws Exception {
    var current = read("/api/director/settings");
    assertThat(current.get("models").size()).isPositive();
    String purpose = Purpose.RENDER_THREAD.key();
    String alias = current.get("models").get(0).get("alias").asString();
    put_("{\"routes\":{\"" + purpose + "\":{\"model\":\"" + alias + "\",\"fallback\":[]}}}").andExpect(status().isOk())
        .andExpect(jsonPath("$.routes['" + purpose + "'].model").value(alias));
    assertThat(llm.routes().get(purpose).model()).isEqualTo(alias);
    put_("{\"routes\":null}").andExpect(status().isOk());

    String locked = lib.blacklist().getFirst().id();
    put_("{\"blacklist\":[]}").andExpect(status().isOk());
    assertThat(lib.isBlacklisted(locked)).as("what the config rules out stays out").isTrue();
    assertThat(read("/api/director/settings").get("blacklist").get(0).get("locked").asBoolean()).isTrue();
  }

  @Test
  @Order(6)
  void aSavedSettingThatNoLongerFitsDoesNotStopTheWorld() throws Exception {
    lane.run(() -> sql.commit(new app.radiance.persistence.UnitOfWork().add("UPDATE world SET settings_json = ?",
        "{\"knobs\":{\"nope\":1},\"blacklist\":[\"gone\"],\"imagesPerDay\":25,\"unknown\":true}")));
    var again = new DirectorSettings(sql, lane, engine, llm, batch, lives);
    again.afterSingletonsInstantiated();
    assertThat(batch.imageLimits().photosPerDay()).as("the good part still applies").isEqualTo(25);
    lane.run(() -> sql.commit(new app.radiance.persistence.UnitOfWork().add("UPDATE world SET settings_json = '{}'")));
    batch.tuneImages(new app.radiance.batch.ImageWork.Limits(12, 9000, 60));
  }

  @Test
  @Order(7)
  void aBatchCanBeRunFromTheConsole_WithItsOwnSpendingWindow() throws Exception {
    mvc.perform(post("/api/director/batch/run").with(R).with(csrf())).andExpect(status().isAccepted());
    long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < end && (batch.isRunning() || sql.queryOne("SELECT COUNT(*) AS n FROM batch_run WHERE kind = 'MANUAL' AND status <> 'RUNNING'", List.of(), r -> r.lng("n")).orElse(0L) == 0)) {
      Thread.sleep(50);
    }
    var view = read("/api/director/batch");
    assertThat(view.get("running").asBoolean()).isFalse();
    assertThat(view.get("runs").get(0).get("kind").asString()).isEqualTo("MANUAL");
    assertThat(view.get("eventMix").size()).isEqualTo(Knobs.defaults().eventMix().size());
    assertThat(read("/api/director/costs").get("windows").toString()).doesNotContain("usd:batch").as("closed once the run is over");
  }

  @Test
  @Order(8)
  void pauseAndResumeThroughTheApi() throws Exception {
    mvc.perform(post("/api/director/time/pause").with(R).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(true));
    assertThat(read("/api/world").get("paused").asBoolean()).isTrue();
    var run = batch.run(NightlyBatch.Kind.MANUAL);
    assertThat(run.notes().toString()).as("the batch leaves a paused world alone").contains("paused");
    mvc.perform(post("/api/director/time/resume").with(R).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(false));
    assertThat(live.awaitIdle(Duration.ofSeconds(20))).isTrue();
  }

  @Test
  @Order(9)
  void promptsOnlyComeFromThePromptFolder() {
    assertThat(llm.prompt("../../build.gradle")).isEmpty();
    assertThat(llm.prompt("nope.json")).isEmpty();
  }
}
