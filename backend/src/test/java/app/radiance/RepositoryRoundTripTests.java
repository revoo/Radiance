package app.radiance;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.cast.ActorRow;
import app.radiance.cast.ActorStateRow;
import app.radiance.cast.CastRepository;
import app.radiance.cast.PersonaRow;
import app.radiance.graph.RelationshipRepository;
import app.radiance.graph.RelationshipRow;
import app.radiance.kernel.AirStatus;
import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.RandomSource;
import app.radiance.kernel.RenderStatus;
import app.radiance.kernel.Ulid;
import app.radiance.kernel.Visibility;
import app.radiance.persistence.TestDatabase;
import app.radiance.persistence.UnitOfWork;
import app.radiance.sim.ScheduledIntentRepository;
import app.radiance.sim.ScheduledIntentRow;
import app.radiance.social.CommentRow;
import app.radiance.social.PostRow;
import app.radiance.social.ReactionRow;
import app.radiance.social.SocialRepository;
import app.radiance.story.ArcRepository;
import app.radiance.story.ArcRow;
import app.radiance.story.CanonRepository;
import app.radiance.story.EntityRow;
import app.radiance.story.EventRepository;
import app.radiance.story.EventRow;
import app.radiance.story.FactRow;
import app.radiance.story.KnowledgeRow;
import app.radiance.world.WorldRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every core aggregate survives a save and load unchanged, and saving twice changes nothing. */
class RepositoryRoundTripTests {

  static final long T = TestDatabase.START;

  @TempDir Path dir;
  TestDatabase db;
  final RandomSource rng = RandomSource.seeded(7);

  @BeforeEach
  void open() {
    db = TestDatabase.leased(dir);
  }

  @AfterEach
  void close() {
    db.close();
  }

  String id(long t) {
    return Ulid.next(t, rng);
  }

  @Test
  void coreAggregatesRoundTrip() {
    var sarah = new ActorRow(id(T), ActorRow.Kind.BOT, "sarah.brennan", "Sarah Brennan", null, null,
        "1993-04-02", "F", "Marketing manager", "Lakeshore Mutual", "Chicago", "Lakeview, Chicago",
        "DATING", "coffee first", "[\"uiuc\",\"lakeshore\"]", T);
    var you = new ActorRow(id(T), ActorRow.Kind.USER, "r", "R", null, null, null, "M", null, null,
        null, null, "SINGLE", null, "[]", T);
    var persona = new PersonaRow(sarah.id(), "{\"openness\":61}", "[\"vaguebooker\"]", "{}",
        "{\"economic\":-0.4}", "[]", "{}", "{\"preset\":\"TYPICAL\"}", "{}", "{}", "[]", "[]",
        "[]", "[]", "{}", "Grew up in Beverly.", null, true);
    var state = new ActorStateRow(sarah.id(), -0.2, 0.7, 0.3, 0.55, "{}", "WORK", 4, null, T);
    var edge = new RelationshipRow(sarah.id(), you.id(), RelationshipRow.Kind.ACQUAINTANCE, 12.5,
        40, 5, 20, 10, 0.3, "[\"finds_funny\"]", null, true, T - 1000, T);

    var arc = new ArcRow(id(T), "romance", ArcRow.Status.ACTIVE, "official", Visibility.PUBLIC, T,
        T, T + 86_400_000, null, "{\"partner\":\"ryan\"}", "Sarah and Ryan are official", null, null);
    var part = new ArcRow.Participant(arc.id(), sarah.id(), "a");
    var car = new EntityRow(id(T), sarah.id(), EntityRow.Kind.CAR, "the Civic",
        "a dented silver 2009 Honda Civic", 1234L, "{\"paid\":4200}", "OWNED", T, T);
    var fact = new FactRow(id(T), sarah.id(), "bought a used tile saw for $80",
        FactRow.Weight.MINOR, FactRow.Source.PROSE, FactRow.Status.PENDING, Visibility.PUBLIC,
        null, T, null, "cmt_x");
    var known = new KnowledgeRow(KnowledgeRow.Subject.ARC, arc.id(), sarah.id(), T);
    var event = new EventRow(id(T + 60_000), T + 60_000, "BREAKUP", "romance", sarah.id(), null,
        arc.id(), 8, Visibility.PUBLIC, EventRow.Source.ARC, AirStatus.SCRIPTED, "{}", null);

    var post = new PostRow(id(T + 60_000), sarah.id(), T + 60_000, PostRow.Kind.STATUS,
        PostRow.Audience.FRIENDS, ContentOrigin.SCRIPT, AirStatus.SCRIPTED, null,
        RenderStatus.PLANNED, "{\"stance\":\"VAGUE\"}", null, null, null, arc.id(), event.id(), 8,
        Fidelity.FULL, "{}", 0);
    var comment = new CommentRow(id(T + 90_000), post.id(), null, you.id(), T + 90_000,
        ContentOrigin.USER, AirStatus.AIRED, "you ok?", null, RenderStatus.RENDERED, null, null,
        "{}");
    var reply = new CommentRow(id(T + 95_000), post.id(), comment.id(), sarah.id(), T + 95_000,
        ContentOrigin.LIVE, AirStatus.SCRIPTED, null, null, RenderStatus.PLANNED, "{}", null, "{}");
    var reaction = new ReactionRow(ReactionRow.Target.POST, post.id(), you.id(),
        ReactionRow.Kind.CARE, T + 91_000, AirStatus.AIRED);
    var intent = new ScheduledIntentRow(id(T + 172_800_000), T + 172_800_000, sarah.id(), "POST",
        null, null, "{}", ScheduledIntentRow.Status.PENDING, event.id());

    var cast = new CastRepository(db.sql);
    var graph = new RelationshipRepository(db.sql);
    var arcs = new ArcRepository(db.sql);
    var canon = new CanonRepository(db.sql);
    var events = new EventRepository(db.sql);
    var social = new SocialRepository(db.sql);
    var intents = new ScheduledIntentRepository(db.sql);

    // Dependency order: actors before everything that references them (SPEC §4.4).
    var uow = new UnitOfWork();
    cast.saveActors(uow, List.of(sarah, you));
    cast.savePersonas(uow, List.of(persona));
    cast.saveStates(uow, List.of(state));
    graph.save(uow, List.of(edge));
    arcs.save(uow, List.of(arc));
    arcs.saveParticipants(uow, List.of(part));
    canon.saveEntities(uow, List.of(car));
    canon.saveFacts(uow, List.of(fact));
    canon.saveKnowledge(uow, List.of(known));
    events.save(uow, List.of(event));
    social.savePosts(uow, List.of(post));
    social.saveComments(uow, List.of(comment, reply));
    social.saveReactions(uow, List.of(reaction));
    intents.save(uow, List.of(intent));
    db.commit(uow);
    db.commit(uow); // a retried unit is a no-op

    assertThat(cast.actors()).containsExactlyInAnyOrder(sarah, you);
    assertThat(cast.personas()).containsExactly(persona);
    assertThat(cast.states()).containsExactly(state);
    assertThat(graph.all()).containsExactly(edge);
    assertThat(graph.toward(you.id())).containsExactly(edge);
    assertThat(arcs.active()).containsExactly(arc);
    assertThat(arcs.participants()).containsExactly(part);
    assertThat(canon.entities()).containsExactly(car);
    assertThat(canon.liveFacts()).containsExactly(fact);
    assertThat(canon.knowledge()).containsExactly(known);
    assertThat(events.find(event.id())).contains(event);
    assertThat(events.scripted()).containsExactly(event);
    assertThat(social.post(post.id())).contains(post);
    assertThat(social.scriptedPosts()).containsExactly(post);
    assertThat(social.commentsOn(post.id())).containsExactly(comment, reply);
    assertThat(social.scriptedComments()).containsExactly(reply);
    assertThat(social.reactionsTo(ReactionRow.Target.POST, post.id())).containsExactly(reaction);
    assertThat(intents.pending()).containsExactly(intent);
  }

  @Test
  void savesOverwriteWithAbsoluteValues() {
    var cast = new CastRepository(db.sql);
    var graph = new RelationshipRepository(db.sql);
    var a = new ActorRow("a", ActorRow.Kind.BOT, "a", "A", null, null, null, "F", null, null, null,
        null, null, null, "[]", T);
    var b = new ActorRow("b", ActorRow.Kind.BOT, "b", "B", null, null, null, "M", null, null, null,
        null, null, null, "[]", T);
    var edge = new RelationshipRow("a", "b", RelationshipRow.Kind.FRIEND, 10, 50, 0, 30, 0, 0.1,
        "[]", null, true, null, T);
    db.commit(graph.save(cast.saveActors(new UnitOfWork(), List.of(a, b)), List.of(edge)));

    var moved = new RelationshipRow("a", "b", RelationshipRow.Kind.CLOSE_FRIEND, 42, 60, 0, 45, 5,
        0.1, "[\"protective_of\"]", "best friends", true, T + 5, T + 5);
    db.commit(graph.save(new UnitOfWork(), List.of(moved)));
    assertThat(graph.all()).containsExactly(moved);
  }

  @Test
  void factsMarkedVoidDropOutOfTheLiveSet() {
    var canon = new CanonRepository(db.sql);
    var pending = new FactRow("f1", "x", "has a dog", FactRow.Weight.MINOR, FactRow.Source.PROSE,
        FactRow.Status.PENDING, Visibility.PUBLIC, null, T, null, null);
    db.commit(canon.saveFacts(new UnitOfWork(), List.of(pending)));
    var voided = new FactRow("f1", "x", "has a dog", FactRow.Weight.MINOR, FactRow.Source.PROSE,
        FactRow.Status.VOID, Visibility.PUBLIC, null, T, null, null);
    db.commit(canon.saveFacts(new UnitOfWork(), List.of(voided)));
    assertThat(canon.liveFacts()).isEmpty();
    assertThat(db.count("fact")).isEqualTo(1);
  }

  @Test
  void theWorldRowSavesWithoutTouchingTheLease() {
    var worlds = new WorldRepository(db.sql);
    var epoch = db.holder.current().orElseThrow().epoch();
    var w = worlds.load().orElseThrow();
    var advanced = new app.radiance.world.WorldRow(w.id(), w.name(), w.networkName(), w.timezone(),
        w.seed(), T + 3_600_000, T + 86_400_000, 0, false, 12.5, T, "{\"edge\":1.0}", w.createdAt());
    db.commit(worlds.save(new UnitOfWork(), advanced));
    assertThat(worlds.load()).contains(advanced);
    assertThat(db.holder.current().orElseThrow().epoch()).isEqualTo(epoch);
    assertThat(db.lease.renew()).isTrue();
  }
}
