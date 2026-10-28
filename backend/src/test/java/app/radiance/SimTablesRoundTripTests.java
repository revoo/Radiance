package app.radiance;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.cast.ActorRow;
import app.radiance.cast.ActorStateRow;
import app.radiance.cast.CastRepository;
import app.radiance.graph.RelationshipRepository;
import app.radiance.graph.RelationshipRow;
import app.radiance.kernel.AirStatus;
import app.radiance.kernel.RenderStatus;
import app.radiance.memory.MemoryRepository;
import app.radiance.memory.MemoryRow;
import app.radiance.messenger.DmRepository;
import app.radiance.messenger.DmRow;
import app.radiance.news.Headline;
import app.radiance.news.HeadlineRepository;
import app.radiance.persistence.TestDatabase;
import app.radiance.persistence.UnitOfWork;
import app.radiance.social.NotificationRepository;
import app.radiance.social.NotificationRow;
import app.radiance.story.ConstraintRepository;
import app.radiance.story.ConstraintRow;
import app.radiance.weather.WeatherHour;
import app.radiance.weather.WeatherRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The tables M4 adds or starts using survive a save and load unchanged. */
class SimTablesRoundTripTests {

  static final long T = TestDatabase.START;

  @TempDir Path dir;
  TestDatabase db;

  @BeforeEach
  void open() {
    db = TestDatabase.leased(dir);
    var a = new ActorRow("a", ActorRow.Kind.BOT, "a.a", "A", null, null, null, "F", null, null, null, null,
        "SINGLE", null, "[]", T);
    var b = new ActorRow("b", ActorRow.Kind.USER, "b.b", "B", null, null, null, "M", null, null, null, null,
        "SINGLE", null, "[]", T);
    db.commit(new CastRepository(db.sql).saveActors(new UnitOfWork(), List.of(a, b)));
  }

  @AfterEach
  void close() {
    db.close();
  }

  @Test
  void newColumnsAndTablesRoundTrip() {
    var cast = new CastRepository(db.sql);
    var graph = new RelationshipRepository(db.sql);
    var state = new ActorStateRow("a", 0.1, 0.6, 0.2, null, "{}", "HOME", 3, null, T, "2026-10-27");
    var edge = new RelationshipRow("a", "b", RelationshipRow.Kind.STRANGER, 5, 30, 0, 5, 0, 0, "[]", null, false,
        null, T, "{\"finds_funny\":" + T + "}");
    var constraint = new ConstraintRow("a", "budget_tight", T, T + 30 * 86_400_000L, "ev1");
    var memory = new MemoryRow("m1", "a", T, MemoryRow.Kind.EPISODE, "argued with B", 5, "[\"b\"]", "[\"clash\"]",
        null, "ev1", false);
    var note = new NotificationRow("n1", "COMMENT", "a", "COMMENT", "c1", T, null);
    var thread = new DmRow.Thread("t1", "a", "b", null, T);
    var message = new DmRow.Message("d1", "t1", "a", T, AirStatus.SCRIPTED, null, RenderStatus.PLANNED, "{}", null,
        null);
    var hour = new WeatherHour(T - T % WeatherHour.HOUR, 34, 27, 0.02, 0.2, 12, 71, WeatherHour.Kind.FAKE, T);
    var headline = new Headline("h1", "fixture://x", "Fixture Wire", "Title", null, T, T, "LOCAL",
        List.of("local"), 0.4, 0.6, "NORMAL", Map.of("economic", -0.2), T);

    var uow = new UnitOfWork();
    cast.saveStates(uow, List.of(state));
    graph.save(uow, List.of(edge));
    new ConstraintRepository(db.sql).save(uow, List.of(constraint));
    new MemoryRepository(db.sql).save(uow, List.of(memory));
    new NotificationRepository(db.sql).save(uow, List.of(note));
    var dms = new DmRepository(db.sql);
    dms.saveThreads(uow, List.of(thread));
    dms.saveMessages(uow, List.of(message));
    new WeatherRepository(db.sql).save(uow, List.of(hour));
    new HeadlineRepository(db.sql).save(uow, List.of(headline));
    db.commit(uow);

    assertThat(cast.states()).containsExactly(state);
    assertThat(graph.all()).containsExactly(edge);
    assertThat(new ConstraintRepository(db.sql).all()).containsExactly(constraint);
    assertThat(new MemoryRepository(db.sql).of("a", T)).containsExactly(memory);
    assertThat(new MemoryRepository(db.sql).tagged("clash")).containsExactly(memory);
    assertThat(new NotificationRepository(db.sql).between(T, T + 1)).containsExactly(note);
    assertThat(dms.threads()).containsExactly(thread);
    assertThat(dms.scripted()).containsExactly(message);
    assertThat(new WeatherRepository(db.sql).between(hour.hour(), hour.hour() + 1)).containsExactly(hour);
    assertThat(new HeadlineRepository(db.sql).publishedBetween(T, T + 1)).containsExactly(headline);

    db.commit(new ConstraintRepository(db.sql).delete(new UnitOfWork(), "a", "budget_tight"));
    assertThat(new ConstraintRepository(db.sql).all()).isEmpty();
  }
}
