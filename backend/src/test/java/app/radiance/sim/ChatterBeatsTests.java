package app.radiance.sim;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.kernel.ContentOrigin;
import app.radiance.kernel.Fidelity;
import app.radiance.kernel.RandomSource;
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
 * R's list of single beats. Each needs nothing remembered later, and where a beat claims something true, code picks
 * the fact and the writer only gets that: a real meter, his real words, real ties, who really lives there.
 */
class ChatterBeatsTests {

  @TempDir Path dir;

  private Chatter chatter(Simulation sim, SimWorld w, Plan[] out, List<String> lines, long seed) {
    long t = w.time + 60_000;
    out[0] = new Plan(w, sim.lib, sim.engine.knobs(), new Slice(t, t + 3_600_000L, Fidelity.FULL),
        new Plan.Inputs(List.of(), null, Set.of(), Map.of(), ContentOrigin.LIVE), new HashMap<>());
    return new Chatter(sim.lib, sim.engine.knobs(), w, out[0], new Chatter.State(), t, t + 20_000, RandomSource.seeded(seed), lines);
  }

  private List<Person> friends(SimWorld w) {
    return w.residents().stream().filter(p -> {
      var e = w.edgeOrNull(p.id, w.userId);
      return e != null && e.connected;
    }).toList();
  }

  @Test
  void gossipOnlyEverSaysWhatTheMeterSays() {
    try (var sim = new Simulation(Simulation.Options.of(7, 2, Persona.WARM, dir)).run()) {
      var w = sim.engine.world();
      var friends = friends(w);
      var b = friends.getFirst();
      w.edge(b.id, w.userId).affinity = 60;
      int seen = 0;
      for (long seed = 0; seed < 25; seed++) {
        var plan = new Plan[1];
        var beat = chatter(sim, w, plan, List.of(), seed).gossip(List.of(b));
        if (beat == null) continue;
        var brief = Brief.parse(beat.script().dms.getFirst().briefJson());
        var m = java.util.regex.Pattern.compile("@([a-z.]+)").matcher(brief.text());
        assertThat(m.find()).isTrue();
        var x = w.byHandle().get(m.group(1));
        double meter = w.edge(x.id, w.userId).affinity;
        if (brief.text().contains("really likes him")) assertThat(meter).isGreaterThanOrEqualTo(55);
        else assertThat(meter).isLessThanOrEqualTo(5);
        assertThat(x.id).isNotEqualTo(b.id);
        seen++;
      }
      assertThat(seen).as("it had something to say").isPositive();
    }
  }

  @Test
  void hisLineIsQuotedWordForWord_AndADmIsNeverAmongThem() {
    try (var sim = new Simulation(Simulation.Options.of(7, 2, Persona.WARM, dir)).run()) {
      var w = sim.engine.world();
      String secret = "CANARY a thing he only said in a DM";
      var actions = new UserActions(sim.sql, sim.sqlLane(), sim.engine);
      var friend = friends(w).getFirst();
      String thread = actions.thread(friend.id);
      sim.sqlLane().run(() -> sim.sql.commit(new app.radiance.persistence.UnitOfWork().add(
          "INSERT INTO dm_message (id, thread_id, sender_id, created_at, air_status, body, render_status, brief_json) VALUES ('private', ?, ?, ?, 'AIRED', ?, 'RENDERED', '{}')",
          thread, w.userId, w.time - 1_000, secret)));
      var said = sim.sql.query("SELECT body FROM comment WHERE author_id = ? AND body IS NOT NULL LIMIT 1", List.of(w.userId), r -> r.str("body"));
      String line = said.isEmpty() ? "some line of his" : said.getFirst();
      int seen = 0;
      for (long seed = 0; seed < 20; seed++) {
        var plan = new Plan[1];
        var beat = chatter(sim, w, plan, List.of(line), seed).quoted(friends(w));
        if (beat == null) continue;
        var brief = Brief.parse(beat.script().comments.getFirst().briefJson());
        assertThat(brief.text()).contains(line).doesNotContain("CANARY");
        assertThat(brief.extra().get("mentions")).asList().contains(w.userId);
        seen++;
      }
      assertThat(seen).isPositive();
      assertThat(new Chatter(sim.lib, sim.engine.knobs(), w, null, new Chatter.State(), w.time, w.time, RandomSource.seeded(1), List.of()).quoted(friends(w))).as("nothing to quote").isNull();
      // the real source of his lines: posts and comments, never DMs
      var beat = actions.chatter(new Chatter.State(), w.time + 60_000, w.time + 80_000, RandomSource.seeded(3));
      assertThat(beat.script().comments.stream().map(c -> c.briefJson()).toList()).noneMatch(x -> x.contains("CANARY"));
    }
  }

  @Test
  void beingTalkedAboutUsesOnlyWhatHeSaid_OrStaysVague() {
    try (var sim = new Simulation(Simulation.Options.of(7, 2, Persona.WARM, dir)).run()) {
      var w = sim.engine.world();
      for (long seed = 0; seed < 10; seed++) {
        var plan = new Plan[1];
        var said = chatter(sim, w, plan, List.of("gen z is cooked"), seed).talkedAbout(friends(w));
        if (said != null) assertThat(Brief.parse(said.script().comments.getFirst().briefJson()).text()).contains("gen z is cooked", "Never invent");
        var vague = chatter(sim, w, plan, List.of(), seed).talkedAbout(friends(w));
        if (vague != null) assertThat(Brief.parse(vague.script().comments.getFirst().briefJson()).text()).contains("stays vague");
      }
    }
  }

  @Test
  void anAwkwardMomentOnlyComesFromARealCoworkerOrRelative() {
    try (var sim = new Simulation(Simulation.Options.of(7, 2, Persona.WARM, dir)).run()) {
      var w = sim.engine.world();
      for (long seed = 0; seed < 15; seed++) {
        var plan = new Plan[1];
        var beat = chatter(sim, w, plan, List.of(), seed).awkward(w.residents());
        if (beat == null) continue;
        var c = beat.script().comments.getFirst();
        var author = w.items.get(c.postId()).authorId;
        assertThat(w.edge(c.authorId(), author).kind).isIn(app.radiance.graph.RelationshipRow.Kind.COWORKER, app.radiance.graph.RelationshipRow.Kind.FAMILY);
      }
    }
  }

  @Test
  void aNeighborhoodEventIsPostedBySomeoneWhoLivesThere_AndNeighborsAreRealOnes() {
    try (var sim = new Simulation(Simulation.Options.of(7, 2, Persona.WARM, dir)).run()) {
      var w = sim.engine.world();
      var lives = new HashMap<String, String>();
      sim.lib.roster().residents().forEach(r -> lives.put(r.resolvedHandle(), r.livesIn() == null ? "" : r.livesIn().endsWith(", Chicago") ? "Chicago" : r.livesIn()));
      int seen = 0;
      for (long seed = 0; seed < 10; seed++) {
        var plan = new Plan[1];
        var beat = chatter(sim, w, plan, List.of(), seed).neighborhood();
        if (beat == null) continue;
        var post = beat.script().posts.getFirst();
        var where = lives.get(w.person(post.authorId()).handle);
        assertThat(Brief.parse(post.briefJson()).text()).contains("where they live in " + where);
        seen++;
      }
      assertThat(seen).isPositive();
    }
  }

  /** Regression: Daniel, across the city, had "lived off Elston for 9 years" because a post about Elston said to notice it too. */
  @Test
  void neighborsOnTheSameBlockNoticeIt_ButTheRestOfChicagoOnlyKnowsWhatThePostSays() {
    assertThat(new Chatter.Local("p", List.of(), 0, true).sameBlock()).isTrue();
    assertThat(new Chatter.Local("p", List.of(), 0, false).sameBlock()).isFalse();
  }

  @Test
  void aSavedSeatNamesHim_AndWrongChatComesInTwoParts_AndASecondLoginDoesntRepeatThem() {
    try (var sim = new Simulation(Simulation.Options.of(7, 2, Persona.WARM, dir)).run()) {
      var w = sim.engine.world();
      var plan = new Plan[1];
      var wrong = chatter(sim, w, plan, List.of(), 5).wrongChat(friends(w));
      if (wrong != null) {
        assertThat(wrong.script().comments).hasSize(2);
        assertThat(wrong.script().comments.get(1).createdAt()).isGreaterThan(wrong.script().comments.get(0).createdAt());
      }
      var asked = chatter(sim, w, plan, List.of(), 6).askedFirst(friends(w));
      assertThat(asked).isNotNull();
      assertThat(asked.script().dms).hasSize(1);

      // moments rest across logins, not only within one
      var remembered = new HashMap<Chatter.Kind, Long>();
      remembered.put(Chatter.Kind.ASKED_FIRST, w.time);
      assertThat(new Chatter.State(remembered).lastOf).containsKey(Chatter.Kind.ASKED_FIRST);
    }
  }
}
