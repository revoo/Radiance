package app.radiance.sim;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.RandomSource;
import app.radiance.sim.model.Item;
import app.radiance.sim.model.Person;
import app.radiance.sim.model.SimWorld;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drop-in mode (ADR 0024): fights on the news that the poster answers, residents who DM R to argue about what he
 * really said, and DM fights that stay fights until he gives ground.
 */
class DropInChatterTests {

  @TempDir Path dir;

  private Chatter chatter(Simulation sim, SimWorld w, Chatter.State st, long seed) {
    long t = w.time + 60_000;
    var plan = new Plan(w, sim.lib, sim.engine.knobs(), new Slice(t, t + 3_600_000L, Fidelity.FULL),
        new Plan.Inputs(List.of(), null, Set.of(), Map.of(), ContentOrigin.LIVE), new HashMap<>());
    return new Chatter(sim.lib, sim.engine.knobs(), w, plan, st, t, t + 20_000, RandomSource.seeded(seed), List.of());
  }

  private List<Person> friends(SimWorld w) {
    return w.residents().stream().filter(p -> {
      var e = w.edgeOrNull(p.id, w.userId);
      return e != null && e.connected;
    }).toList();
  }

  @Test
  void aCallOutQuotesHisPublicWordsExactly_ToHim_AndNeverTheSameLineTwice() {
    try (var sim = new Simulation(Simulation.Options.of(7, 2, Persona.WARM, dir)).run()) {
      var w = sim.engine.world();
      var st = new Chatter.State().dropIn(0.25);
      st.heSaidLately = List.of("pineapple on pizza is objectively correct");
      var first = chatter(sim, w, st, 1).calledOut(friends(w));
      assertThat(first).isNotNull();
      assertThat(first.kind()).isEqualTo(Chatter.Kind.CALLED_OUT);
      var dm = first.script().dms.getFirst();
      var brief = Brief.parse(dm.briefJson());
      assertThat(brief.text()).contains("pineapple on pizza is objectively correct");
      assertThat(brief.toward()).isEqualTo(w.userId);
      assertThat(brief.stance()).isIn("DISAGREE", "MOCK");
      assertThat(brief.extra()).containsEntry("beat", "calledOut");
      assertThat(dm.senderId()).isNotEqualTo(w.userId);
      assertThat(chatter(sim, w, st, 2).calledOut(friends(w))).as("that line has been called out already").isNull();
      assertThat(chatter(sim, w, new Chatter.State().dropIn(0.25), 3).calledOut(friends(w))).as("nothing he said").isNull();
    }
  }

  @Test
  void hisLatelySaidLinesAreOnlyPublic_NeverADm() {
    try (var sim = new Simulation(Simulation.Options.of(7, 2, Persona.WARM, dir)).run()) {
      var w = sim.engine.world();
      var actions = new UserActions(sim.sql, sim.sqlLane(), sim.engine);
      String thread = actions.thread(friends(w).getFirst().id);
      sim.sqlLane().run(() -> sim.sql.commit(new app.radiance.persistence.UnitOfWork().add(
          "INSERT INTO dm_message (id, thread_id, sender_id, created_at, air_status, body, render_status, brief_json) VALUES ('private', ?, ?, ?, 'AIRED', ?, 'RENDERED', '{}')",
          thread, w.userId, w.time - 1_000, "CANARY said only in a DM")));
      var st = new Chatter.State().dropIn(0.25);
      for (long seed = 0; seed < 15; seed++) {
        var beat = actions.chatter(st, w.time + 60_000 + seed, w.time + 80_000 + seed, RandomSource.seeded(seed));
        assertThat(st.heSaidLately).noneMatch(x -> x.contains("CANARY"));
        assertThat(beat.script().dms.stream().map(m -> m.briefJson()).toList()).noneMatch(x -> x.contains("CANARY"));
      }
    }
  }

  @Test
  void aFightOnATakeIsAnsweredByThePoster() {
    try (var sim = new Simulation(Simulation.Options.of(7, 4, Persona.WARM, dir)).run()) {
      var w = sim.engine.world();
      var news = w.items.values().stream().filter(i -> i.type == Item.Type.POST && i.headlineId != null && i.aired() && !i.authorId.equals(w.userId)).toList();
      assertThat(news).as("the simulated days have takes on the news").isNotEmpty();
      // make the newest take recent enough to be a live thread
      long t = news.stream().mapToLong(i -> i.time).max().orElseThrow() + 30 * 60_000L;
      var st = new Chatter.State().dropIn(0.25);
      var plan = new Plan(w, sim.lib, sim.engine.knobs(), new Slice(t, t + 3_600_000L, Fidelity.FULL),
          new Plan.Inputs(List.of(), null, Set.of(), Map.of(), ContentOrigin.LIVE), new HashMap<>());
      var beat = new Chatter(sim.lib, sim.engine.knobs(), w, plan, st, t, t + 20_000, RandomSource.seeded(5), List.of()).pickFight(w.residents());
      assertThat(beat).isNotNull();
      var c = beat.script().comments.getFirst();
      var post = w.items.get(c.postId());
      assertThat(post.headlineId).isNotNull();
      assertThat(c.authorId()).isNotEqualTo(post.authorId);
      assertThat(Brief.parse(c.briefJson()).toward()).isEqualTo(post.authorId);
      assertThat(st.argument).isNotNull();
      assertThat(st.argument.nextUp).as("the poster answers next").isEqualTo(post.authorId);
      assertThat(st.argument.turnsLeft).isGreaterThanOrEqualTo(5);
    }
  }

  @Test
  void movesRestForAQuarterAsLongInDropIn() {
    try (var sim = new Simulation(Simulation.Options.of(7, 2, Persona.WARM, dir)).run()) {
      var w = sim.engine.world();
      long t = w.time + 60_000;
      var normal = new Chatter.State();
      var dropIn = new Chatter.State().dropIn(0.25);
      normal.lastOf.put(Chatter.Kind.GOSSIP, t - 2 * 3_600_000L);
      dropIn.lastOf.put(Chatter.Kind.GOSSIP, t - 2 * 3_600_000L);
      var friend = friends(w).getFirst();
      w.edge(friend.id, w.userId).affinity = 60;
      // gossip rests six hours; at a quarter of that, two hours is enough
      var fan = w.residents().stream().filter(x -> !x.id.equals(friend.id)).findFirst().orElseThrow();
      w.edge(fan.id, w.userId).affinity = 80;
      w.edge(friend.id, fan.id).connected = true;
      int normalGossip = 0, dropInGossip = 0;
      for (long seed = 0; seed < 40; seed++) {
        var a = chatter(sim, w, normal, seed).next();
        var b = chatter(sim, w, dropIn, seed).next();
        if (a.kind() == Chatter.Kind.GOSSIP) normalGossip++;
        if (b.kind() == Chatter.Kind.GOSSIP) dropInGossip++;
        normal.lastOf.put(Chatter.Kind.GOSSIP, t - 2 * 3_600_000L);
        dropIn.lastOf.put(Chatter.Kind.GOSSIP, t - 2 * 3_600_000L);
      }
      assertThat(normalGossip).as("still resting at normal pace").isZero();
      assertThat(dropInGossip).as("rested at a quarter of the time").isPositive();
    }
  }

  @Test
  void aDmFightStaysAFight_UntilHeGivesGround() {
    try (var sim = new Simulation(Simulation.Options.of(7, 2, Persona.WARM, dir)).run()) {
      var w = sim.engine.world();
      var actions = new UserActions(sim.sql, sim.sqlLane(), sim.engine);
      var st = new Chatter.State().dropIn(0.25);
      st.heSaidLately = List.of("the bears are going to the super bowl");
      var beat = chatter(sim, w, st, 4).calledOut(friends(w));
      assertThat(beat).isNotNull();
      actions.save(beat.script());
      var opener = beat.script().dms.getFirst();
      long t = opener.createdAt() + 60_000;
      sim.engine.advanceTo(t);

      var hostile = new UserActions.Reading(Map.of(), List.of(), 0.5, "DISAGREE", null);
      var msg = actions.dm(opener.threadId(), "lol no, you clearly don't watch football", hostile, t).orElseThrow();
      var reply = actions.dmReply(opener.threadId(), msg, 0.0, t);
      assertThat(reply.script().dms).isNotEmpty();
      for (var m : reply.script().dms) {
        var brief = Brief.parse(m.briefJson());
        assertThat(brief.stance()).isIn("DISAGREE", "MOCK");
        assertThat(brief.interaction()).isEqualTo("dm_curt");
        assertThat(brief.extra()).containsEntry("beat", "calledOut");
      }
      assertThat(Brief.parse(reply.script().dms.getFirst().briefJson()).text()).contains("don't back down");

      long later = t + 120_000;
      sim.engine.advanceTo(later);
      var nice = new UserActions.Reading(Map.of(), List.of(), 0, "SUPPORT", null);
      var sorry = actions.dm(opener.threadId(), "ok fair, you got me", nice, later).orElseThrow();
      var after = actions.dmReply(opener.threadId(), sorry, 0.0, later);
      var brief = Brief.parse(after.script().dms.getFirst().briefJson());
      assertThat(brief.stance()).isEqualTo("JOKE");
      assertThat(brief.text()).contains("backing down");
    }
  }
}
