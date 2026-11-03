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
  @Autowired app.radiance.persistence.WriterLane writer;

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

  @Test
  @Order(6)
  void theStreamSaysHelloAsServerSentEvents() throws Exception {
    var result = mvc.perform(get("/api/stream").with(R)).andExpect(status().isOk()).andReturn();
    assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
    assertThat(result.getResponse().getContentAsString()).contains("event:hello");
  }

  @Test
  @Order(7)
  void messengerOpensSendsListsAndReads() throws Exception {
    var friends = Json.tree(mvc.perform(get("/api/people").param("filter", "friends").with(R)).andReturn().getResponse()
        .getContentAsString());
    String handle = friends.get(0).get("handle").asString();
    var opened = Json.tree(mvc.perform(post("/api/dm/threads").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"handle\":\"" + handle + "\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.with.handle").value(handle))
        .andReturn().getResponse().getContentAsString());
    String thread = opened.get("id").asString();
    // the same thread the second time
    mvc.perform(post("/api/dm/threads").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"handle\":\"" + handle + "\"}")).andExpect(jsonPath("$.id").value(thread));

    mvc.perform(post("/api/dm/threads/" + thread + "/messages").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"hey, coffee this week?\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.you").value(true));
    mvc.perform(get("/api/dm/threads/" + thread + "/messages").with(R)).andExpect(status().isOk())
        .andExpect(jsonPath("$.messages[0].body").value("hey, coffee this week?"))
        .andExpect(jsonPath("$.thread.with.handle").value(handle));
    mvc.perform(get("/api/dm/threads").with(R)).andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + thread + "')]").exists());
    mvc.perform(post("/api/dm/threads/" + thread + "/read").with(R).with(csrf())).andExpect(status().isNoContent());

    mvc.perform(post("/api/dm/threads/" + thread + "/messages").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"text\":\"   \"}")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/dm/threads/not-a-thread/messages").with(R)).andExpect(status().isNotFound());
    mvc.perform(post("/api/dm/threads").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"handle\":\"nobody.here\"}")).andExpect(status().isNotFound());
  }

  @Test
  @Order(8)
  void dmsAreNotInTheBell() throws Exception {
    mvc.perform(post("/api/notifications/read").with(R).with(csrf())).andExpect(status().isNoContent());
    String someone = sql.queryOne("SELECT id FROM actor WHERE kind = 'BOT' LIMIT 1", List.of(), r -> r.str("id")).orElseThrow();
    writer.run(() -> sql.commit(new app.radiance.persistence.UnitOfWork().add("INSERT INTO notification (id, kind, actor_id,"
        + " ref_type, ref_id, created_at) VALUES ('n-dm-1', 'DM', ?, 'DM', 'm-1', 1)", someone)));
    mvc.perform(get("/api/notifications").with(R)).andExpect(jsonPath("$.unread").value(0))
        .andExpect(jsonPath("$.items[?(@.kind == 'DM')]").isEmpty());
  }

  @Test
  @Order(9)
  void aNewsTakeCarriesItsLinkCardAndTheRailCountsOnlyWhatAired() throws Exception {
    long now = System.currentTimeMillis();
    var posts = sql.query("SELECT id FROM post WHERE air_status = 'AIRED' AND render_status = 'RENDERED' AND body IS NOT NULL"
        + " AND headline_id IS NULL ORDER BY id LIMIT 3", java.util.List.of(), r -> r.str("id"));
    assertThat(posts).hasSize(3);
    String insert = "INSERT INTO headline (id, url, source, title, published_at, fetched_at, classified_at)"
        + " VALUES (?, ?, ?, ?, ?, ?, ?)";
    writer.run(() -> sql.commit(new app.radiance.persistence.UnitOfWork()
        .add(insert, "real", "https://thehill.com/senate", "The Hill", "Senate passes the budget", now, now, now)
        .add(insert, "fake", "fixture://api-test/1", "Fixture Wire", "A made-up story", now, now, now)
        .add(insert, "hidden", "https://example.com/spoiler", "Wire", "A story nobody has posted yet", now, now, now)
        .add("UPDATE post SET headline_id = 'real', created_at = ?, comment_count = 1000, audience = 'PUBLIC' WHERE id = ?", now - 60_000, posts.get(0))
        .add("UPDATE post SET headline_id = 'fake', created_at = ?, audience = 'PUBLIC' WHERE id = ?", now - 60_000, posts.get(1))
        .add("UPDATE post SET headline_id = 'hidden', created_at = ?, air_status = 'SCRIPTED' WHERE id = ?", now - 60_000, posts.get(2))
        .add("INSERT INTO weather_hour (hour, temp_f, feels_like_f, precip_in, snow_in, wind_mph, wmo_code, kind, fetched_at)"
            + " VALUES (?, 34.4, 27, 0, 0.1, 5, 71, 'FORECAST', ?) ON CONFLICT (hour) DO UPDATE SET temp_f = 34.4,"
            + " feels_like_f = 27, snow_in = 0.1, wmo_code = 71", now - now % 3_600_000L, now)
        .add("INSERT INTO weather_hour (hour, temp_f, feels_like_f, precip_in, snow_in, wind_mph, wmo_code, kind, fetched_at)"
            + " VALUES (?, 34.4, 27, 0, 0.1, 5, 71, 'FORECAST', ?) ON CONFLICT (hour) DO UPDATE SET temp_f = 34.4,"
            + " feels_like_f = 27, snow_in = 0.1, wmo_code = 71", now - now % 3_600_000L + 3_600_000L, now)));

    mvc.perform(get("/api/posts/" + posts.get(0)).with(R)).andExpect(status().isOk())
        .andExpect(jsonPath("$.link.url").value("https://thehill.com/senate"))
        .andExpect(jsonPath("$.link.source").value("The Hill"))
        .andExpect(jsonPath("$.link.title").value("Senate passes the budget"));
    mvc.perform(get("/api/posts/" + posts.get(1)).with(R)).andExpect(status().isOk())
        .andExpect(jsonPath("$.link.url").doesNotExist())
        .andExpect(jsonPath("$.link.title").value("A made-up story"));

    mvc.perform(get("/api/rail").with(R)).andExpect(status().isOk())
        .andExpect(jsonPath("$.weather.tempF").value(34))
        .andExpect(jsonPath("$.weather.sky").value("light snow"))
        .andExpect(jsonPath("$.weather.wmo").value(71))
        .andExpect(jsonPath("$.weather.feelsLikeF").value(27))
        .andExpect(jsonPath("$.weather.highF").isNumber())
        .andExpect(jsonPath("$.talkedAbout[0].people[0].name").isNotEmpty())
        .andExpect(jsonPath("$.talkedAbout[0].people.length()").value(1))
        .andExpect(jsonPath("$.talkedAbout[0].title").value("Senate passes the budget"))
        .andExpect(jsonPath("$.talkedAbout[0].posts").value(1))
        .andExpect(jsonPath("$.talkedAbout[0].comments").value(1000))
        .andExpect(jsonPath("$.talkedAbout[?(@.title == 'A story nobody has posted yet')]").isEmpty());
  }

  @Test
  @Order(10)
  void theRailNeedsALogin() throws Exception {
    mvc.perform(get("/api/rail")).andExpect(status().isUnauthorized());
  }

  @Test
  @Order(11)
  void aResidentsMeterShowsOnTheirProfileAndEveryoneIsListedByItWithATrend() throws Exception {
    String me = sql.queryOne("SELECT to_id FROM relationship WHERE from_id IN (SELECT id FROM actor WHERE kind = 'BOT')"
        + " AND to_id NOT IN (SELECT id FROM actor WHERE kind = 'BOT') LIMIT 1", java.util.List.of(), r -> r.str("to_id")).orElseThrow();
    var rows = sql.query("SELECT r.from_id, a.handle FROM relationship r JOIN actor a ON a.id = r.from_id WHERE r.to_id = ?"
        + " ORDER BY r.from_id LIMIT 2", java.util.List.of(me), r -> new String[] {r.str("from_id"), r.str("handle")});
    String today = java.time.LocalDate.now(java.time.ZoneId.of("America/Chicago")).toString();
    String long_ago = java.time.LocalDate.now(java.time.ZoneId.of("America/Chicago")).minusDays(40).toString();
    writer.run(() -> sql.commit(new app.radiance.persistence.UnitOfWork()
        .add("UPDATE relationship SET affinity = 90.4 WHERE from_id = ? AND to_id = ?", rows.get(0)[0], me)
        .add("UPDATE relationship SET affinity = -95 WHERE from_id = ? AND to_id = ?", rows.get(1)[0], me)
        .add("DELETE FROM meter_snapshot")
        .add("INSERT INTO meter_snapshot (day, actor_id, affinity) VALUES (?, ?, 80)", today, rows.get(0)[0])
        .add("INSERT INTO meter_snapshot (day, actor_id, affinity) VALUES (?, ?, 5)", long_ago, rows.get(1)[0])));

    mvc.perform(get("/api/people/" + rows.get(0)[1]).with(R)).andExpect(jsonPath("$.meter").value(90));
    mvc.perform(get("/api/me/relationships").with(R)).andExpect(status().isOk())
        .andExpect(jsonPath("$[0].actor.handle").value(rows.get(0)[1]))
        .andExpect(jsonPath("$[0].meter").value(90))
        .andExpect(jsonPath("$[0].trend").value(10))
        .andExpect(jsonPath("$[-1:].actor.handle").value(rows.get(1)[1]))
        .andExpect(jsonPath("$[-1:].meter").value(-95))
        .andExpect(jsonPath("$[-1:].trend").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
    mvc.perform(get("/api/me").with(R)).andExpect(jsonPath("$.likeMeters").value(true));
    mvc.perform(get("/api/me/relationships")).andExpect(status().isUnauthorized());
  }

  @Test
  @Order(12)
  void camerasAreOffUntilConfigured_AndNeedALogin() throws Exception {
    mvc.perform(get("/api/cameras").with(R)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    mvc.perform(get("/api/cameras/loop/image").with(R)).andExpect(status().isNotFound());
    mvc.perform(get("/api/cameras")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/cameras/loop/image")).andExpect(status().isUnauthorized());
  }

  @Test
  void camerasHideWhenTheWorldsClockHasMovedOffRealTime() {
    long wall = 1_000_000_000L;
    assertThat(CamerasController.inRealTime(wall, wall)).isTrue();
    assertThat(CamerasController.inRealTime(wall + CamerasController.MAX_DRIFT, wall)).isTrue();
    assertThat(CamerasController.inRealTime(wall - CamerasController.MAX_DRIFT, wall)).isTrue();
    assertThat(CamerasController.inRealTime(wall + CamerasController.MAX_DRIFT + 1, wall)).as("fast-forwarded").isFalse();
    assertThat(CamerasController.inRealTime(wall - CamerasController.MAX_DRIFT - 1, wall)).as("paused or behind").isFalse();
  }

  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

  String uploadPhoto() throws Exception {
    var started = mvc.perform(post("/api/media/uploads").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mime\":\"image/jpeg\",\"bytes\":6,\"width\":1600,\"height\":1200}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var tree = app.radiance.kernel.Json.tree(started);
    String id = tree.path("mediaId").asString();
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(tree.path("putUrl").asString()).with(R).with(csrf()).content(JPEG))
        .andExpect(status().isNoContent());
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(tree.path("thumbPutUrl").asString()).with(R).with(csrf()).content(JPEG))
        .andExpect(status().isNoContent());
    return id;
  }

  @Test
  @Order(13)
  void aPhotoGoesUp_GetsAPost_AndComesBackThroughTheLogin() throws Exception {
    String id = uploadPhoto();
    // not finished yet: it can't be seen or used
    mvc.perform(get("/api/media/" + id).with(R)).andExpect(status().isNotFound());
    mvc.perform(post("/api/posts").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hi\",\"mediaIds\":[\"" + id + "\"]}"))
        .andExpect(status().isBadRequest());

    mvc.perform(post("/api/media/" + id + "/complete").with(R).with(csrf())).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.url").value("/api/media/" + id));

    var made = mvc.perform(post("/api/posts").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"\",\"mediaIds\":[\"" + id + "\"]}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("PHOTO")).andExpect(jsonPath("$.media.id").value(id))
        .andReturn().getResponse().getContentAsString();
    assertThat(app.radiance.kernel.Json.tree(made).path("body").asString()).isEmpty();

    var image = mvc.perform(get("/api/media/" + id).with(R)).andExpect(status().isOk()).andReturn().getResponse();
    assertThat(image.getContentAsByteArray()).isEqualTo(JPEG);
    assertThat(image.getContentType()).isEqualTo("image/jpeg");
    assertThat(image.getHeader("Cache-Control")).contains("private", "immutable");
    mvc.perform(get("/api/media/" + id + "?size=thumb").with(R)).andExpect(status().isOk());
    mvc.perform(get("/api/media/" + id)).andExpect(status().isUnauthorized());

    String me = sql.queryOne("SELECT handle FROM actor WHERE kind = 'USER'", java.util.List.of(), r -> r.str("handle")).orElseThrow();
    mvc.perform(get("/api/people/" + me + "/photos").with(R)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
  }

  @Test
  @Order(14)
  void uploadsAreRefusedWithoutCsrf_AndAnythingOddIsABadRequest() throws Exception {
    String body = "{\"mime\":\"image/jpeg\",\"bytes\":6}";
    mvc.perform(post("/api/media/uploads").with(R).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    mvc.perform(post("/api/media/uploads").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"mime\":\"image/svg+xml\",\"bytes\":6}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/media/nope/complete").with(R).with(csrf())).andExpect(status().isBadRequest());
    mvc.perform(get("/api/media/nope").with(R)).andExpect(status().isNotFound());
    mvc.perform(post("/api/posts").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"x\",\"mediaIds\":[\"a\",\"b\"]}"))
        .andExpect(status().isBadRequest());
    // no words and no photo
    mvc.perform(post("/api/posts").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"\"}")).andExpect(status().isBadRequest());
  }

  @Test
  @Order(15)
  void rPicksHisPicture_AndItFollowsHimAround() throws Exception {
    String id = uploadPhoto();
    mvc.perform(post("/api/media/" + id + "/complete").with(R).with(csrf())).andExpect(status().isOk());
    mvc.perform(post("/api/profile/avatar").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":\"" + id + "\"}")).andExpect(status().isNoContent());
    mvc.perform(get("/api/me").with(R)).andExpect(jsonPath("$.actor.avatarUrl").value("/api/media/" + id + "?size=thumb"));
    mvc.perform(post("/api/profile/avatar").with(R).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":\"nope\"}")).andExpect(status().isBadRequest());
  }

  @Test
  @Order(16)
  void theRailKnowsWhoseBirthdayItIs_AmongRsFriends() throws Exception {
    String user = sql.queryOne("SELECT id FROM actor WHERE kind = 'USER'", java.util.List.of(), r -> r.str("id")).orElseThrow();
    String friend = sql.queryOne("SELECT to_id FROM relationship WHERE from_id = ? AND connected = 1 LIMIT 1", java.util.List.of(user), r -> r.str("to_id")).orElseThrow();
    var tree = app.radiance.kernel.Json.tree(mvc.perform(get("/api/world").with(R)).andReturn().getResponse().getContentAsString());
    var zone = java.time.ZoneId.of(tree.path("timezone").asString());
    var today = java.time.Instant.ofEpochMilli(tree.path("worldTime").asLong()).atZone(zone).toLocalDate();
    writer.run(() -> sql.commit(new app.radiance.persistence.UnitOfWork()
        .add("UPDATE actor SET birth_date = ? WHERE id = ?", today.withYear(1990).toString(), friend)));
    mvc.perform(get("/api/rail").with(R)).andExpect(status().isOk())
        .andExpect(jsonPath("$.birthdays[?(@.id == '" + friend + "')]").exists())
        .andExpect(jsonPath("$.online").isArray());
    writer.run(() -> sql.commit(new app.radiance.persistence.UnitOfWork()
        .add("UPDATE actor SET birth_date = ? WHERE id = ?", today.plusDays(2).withYear(1990).toString(), friend)));
    mvc.perform(get("/api/rail").with(R)).andExpect(jsonPath("$.birthdays[?(@.id == '" + friend + "')]").doesNotExist());
  }
}
