package app.radiance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.radiance.kernel.Json;
import app.radiance.persistence.Sql;
import app.radiance.world.genesis.GenesisService;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The REST API end to end on a genesis world (SPEC §7, §12 M7): log in, onboard, a feed that's ready at
 * once, R's post and comment echoed immediately, reactions, people, notifications and the recap.
 */
@SpringBootTest(properties = "radiance.genesis.seed-file=${java.io.tmpdir}/radiance-test/api-seed.json")
@AutoConfigureMockMvc
@ActiveProfiles({"offline", "test"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiTests {

  @Autowired MockMvc mvc;
  @Autowired GenesisService genesis;
  @Autowired Sql sql;

  static final RequestPostProcessor R = user("dev");

  @BeforeAll
  void world() throws Exception {
    genesis.preview(7, true, false);
    genesis.commit();
  }

  @Test
  @Order(1)
  void loginIsJsonAndFailuresAreRateLimited() throws Exception {
    mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("username", "dev").param("password", "wrong").with(r -> { r.setRemoteAddr("10.0.0.9"); return r; }))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
    for (int i = 0; i < 5; i++) {
      mvc.perform(post("/api/auth/login").with(csrf()).param("username", "dev").param("password", "nope")
          .with(r -> { r.setRemoteAddr("10.0.0.7"); return r; }));
    }
    mvc.perform(post("/api/auth/login").with(csrf()).param("username", "dev").param("password", "nope")
            .with(r -> { r.setRemoteAddr("10.0.0.7"); return r; }))
        .andExpect(status().isTooManyRequests());
    mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/posts").with(R).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"no csrf\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @Order(2)
  void onboardingThenAReadyFeed() throws Exception {
    mvc.perform(get("/api/me").with(R)).andExpect(status().isOk())
        .andExpect(jsonPath("$.worldReady").value(true))
        .andExpect(jsonPath("$.onboarded").value(false));
    mvc.perform(put("/api/profile").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
        {"name": "Sam Rivera", "bio": "Engineer, runner.", "employer": "Acme", "livesIn": "Logan Square",
         "circles": ["softball"], "interests": ["tech", "fitness"], "backstory": "LOCAL"}"""))
        .andExpect(status().isOk());
    mvc.perform(get("/api/me").with(R)).andExpect(jsonPath("$.onboarded").value(true))
        .andExpect(jsonPath("$.actor.name").value("Sam Rivera"));

    long t0 = System.nanoTime();
    var feed = mvc.perform(get("/api/feed").with(R)).andExpect(status().isOk()).andReturn().getResponse()
        .getContentAsString();
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(ms).as("the first screen in under a second").isLessThan(1_000);
    var items = Json.tree(feed).get("items");
    assertThat(items.size()).as("two weeks of history are already there").isPositive();
    for (var p : items) {
      assertThat(p.get("body").asString()).isNotBlank();
      assertThat(p.get("author").get("name").asString()).isNotBlank();
    }
    String cursor = Json.tree(feed).get("nextCursor").asString();
    if (cursor != null && !cursor.isEmpty()) {
      mvc.perform(get("/api/feed").param("cursor", cursor).with(R)).andExpect(status().isOk());
    }
    mvc.perform(get("/api/feed").param("mode", "top").with(R)).andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    mvc.perform(get("/api/world/recap").with(R)).andExpect(status().isOk()).andExpect(jsonPath("$.lines").isArray());
    mvc.perform(post("/api/session/heartbeat").with(R).with(csrf())).andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionActive").value(true));
  }

  @Test
  @Order(3)
  void rsPostCommentAndReactionsAreEchoedAtOnce() throws Exception {
    var made = mvc.perform(post("/api/posts").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"Rainy Saturday. Coffee, a book and nowhere to be.\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.author.you").value(true))
        .andReturn().getResponse().getContentAsString();
    String id = Json.tree(made).get("id").asString();
    mvc.perform(get("/api/feed").with(R)).andExpect(jsonPath("$.items[0].id").value(id));
    mvc.perform(post("/api/posts/" + id + "/comments").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"Honestly the best kind of day.\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.body").value("Honestly the best kind of day."));
    mvc.perform(get("/api/posts/" + id + "/comments").with(R)).andExpect(status().isOk())
        .andExpect(jsonPath("$[0].author.you").value(true));

    String other = null;
    for (var p : Json.tree(mvc.perform(get("/api/feed").with(R)).andReturn().getResponse().getContentAsString()).get("items")) {
      if (!p.get("author").get("you").asBoolean()) {
        other = p.get("id").asString();
        break;
      }
    }
    assertThat(other).as("a friend's post in the feed").isNotNull();
    String stranger = sql.queryOne("SELECT p.id FROM post p WHERE p.audience = 'FRIENDS' AND p.air_status = 'AIRED'"
        + " AND p.author_id NOT IN (SELECT to_id FROM relationship WHERE from_id = (SELECT id FROM actor WHERE kind = 'USER')"
        + " AND connected = 1) AND p.author_id <> (SELECT id FROM actor WHERE kind = 'USER') LIMIT 1", List.of(),
        r -> r.str("id")).orElse(null);
    if (stranger != null) {
      mvc.perform(put("/api/reactions").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON)
          .content("{\"targetType\":\"POST\",\"targetId\":\"" + stranger + "\",\"kind\":\"LIKE\"}"))
          .andExpect(status().isNotFound());
    }
    mvc.perform(put("/api/reactions").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"targetType\":\"POST\",\"targetId\":\"" + other + "\",\"kind\":\"LOVE\"}"))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/posts/" + other).with(R)).andExpect(jsonPath("$.reactions.mine").value("LOVE"));
    mvc.perform(delete("/api/reactions/POST/" + other).with(R).with(csrf())).andExpect(status().isNoContent());
    mvc.perform(get("/api/posts/" + other).with(R)).andExpect(jsonPath("$.reactions.mine").doesNotExist());
    mvc.perform(put("/api/reactions").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"targetType\":\"POST\",\"targetId\":\"" + other + "\",\"kind\":\"MEH\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    mvc.perform(get("/api/posts/nope").with(R)).andExpect(status().isNotFound());
  }

  @Test
  @Order(4)
  void peopleNotificationsAndThePacingMonitor() throws Exception {
    var people = Json.tree(mvc.perform(get("/api/people").param("filter", "friends").with(R)).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
    assertThat(people.size()).isPositive();
    String handle = people.get(0).get("handle").asString();
    mvc.perform(get("/api/people/" + handle).with(R)).andExpect(status().isOk())
        .andExpect(jsonPath("$.friend").value(true));
    mvc.perform(get("/api/people/" + handle + "/posts").with(R)).andExpect(status().isOk());
    mvc.perform(get("/api/notifications").with(R)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
    mvc.perform(post("/api/notifications/read").with(R).with(csrf())).andExpect(status().isNoContent());
    mvc.perform(get("/api/notifications").with(R)).andExpect(jsonPath("$.unread").value(0));
    mvc.perform(get("/api/director/pacer").with(R)).andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionId").isNotEmpty());
    mvc.perform(put("/api/profile").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"bio\": \"Runner, coder, deep-dish skeptic.\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.bio").value("Runner, coder, deep-dish skeptic."));
  }
}
