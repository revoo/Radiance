package app.radiance.sim;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.content.ClasspathContent;
import app.radiance.content.ContentLibrary;
import app.radiance.content.ContentProperties;
import app.radiance.persistence.TestDatabase;
import app.radiance.persistence.UnitOfWork;
import app.radiance.sim.model.Drift;
import app.radiance.sim.model.Tag;
import app.radiance.world.genesis.SeedImporter;
import app.radiance.world.genesis.SkeletonGenerator;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The committed world loads into memory, copies independently and writes back only what changed. */
class ModelStoreTests {

  static final ContentLibrary LIB = ClasspathContent.load(List.of(
      new ContentProperties.BlacklistEntry("pregnancy", List.of("pregnant"))));

  @TempDir Path dir;
  TestDatabase db;
  ModelStore store;

  @BeforeEach
  void world() {
    db = TestDatabase.leased(dir);
    var seed = SkeletonGenerator.generate(LIB, new SkeletonGenerator.Settings(7, 3, 5, 4, LocalDate.of(2026, 10, 27)));
    new SeedImporter(db.sql, db.lane, LIB).importSeed(seed, TestDatabase.START);
    store = new ModelStore(db.sql, LIB);
  }

  @AfterEach
  void close() {
    db.close();
  }

  @Test
  void loadsTheImportedWorld() {
    var w = store.load();
    assertThat(w.seed).isEqualTo(7);
    assertThat(w.residents()).hasSize(30);
    assertThat(w.userId).isNull();
    assertThat(w.edges).hasSizeBetween(150, 300);
    assertThat(w.arcs.values()).allMatch(a -> a.active());
    var h = w.byHandle();
    var jessica = h.get("jessica.vogel");
    var nick = h.get("nick.grabowski");
    assertThat(jessica.routine.weekday()).hasSize(24);
    assertThat(h.get("sarah.brennan").partnerId).isEqualTo(h.get("ryan.kessler").id);
    // the secret romance's phase tags are derived from the arc, not stored
    assertThat(w.activeTags(jessica.id, w.time, LIB)).contains("in_relationship:" + nick.id, "secret:relationship", "single");
    assertThat(w.tags).isEmpty();
    var romance = w.arcs.values().stream().filter(a -> a.defId.equals("romance") && a.involves(jessica.id)).findFirst()
        .orElseThrow();
    assertThat(w.knows("ARC", romance.id, nick.id)).isTrue();
    assertThat(w.knows("ARC", romance.id, h.get("megan.quinlan").id)).isFalse();
  }

  @Test
  void aCopyIsIndependent() {
    var w = store.load();
    var c = w.copy();
    var jake = c.byHandle().get("jake.zielinski");
    c.person(jake.id).mood = -0.9;
    c.edgesFrom(jake.id).getFirst().affinity = -99;
    c.arcs.firstEntry().getValue().phase = "elsewhere";
    assertThat(w.person(jake.id).mood).isNotEqualTo(-0.9);
    assertThat(w.edgesFrom(jake.id).getFirst().affinity).isNotEqualTo(-99);
    assertThat(w.arcs.firstEntry().getValue().phase).isNotEqualTo("elsewhere");
  }

  @Test
  void writesBackOnlyWhatChangedAndReloadsTheSame() {
    var w = store.load();
    assertThat(store.write(new UnitOfWork(), w).size()).isEqualTo(1); // nothing dirty: just the world row
    var jake = w.byHandle().get("jake.zielinski");
    Drift.advance(w.person(jake.id), w.seed, 36, w.time + 5 * 3_600_000L);
    var e = w.edgesFrom(jake.id).getFirst();
    e.affinity = -42;
    e.pending.put("finds_funny", w.time);
    e.dirty = true;
    w.tags.computeIfAbsent(jake.id, k -> new java.util.TreeMap<>())
        .put("budget_tight", new Tag("budget_tight", w.time, w.time + 30 * 86_400_000L, null));
    w.learn("ARC", w.arcs.firstKey(), jake.id);
    w.time += 3_600_000L;
    w.tension = 12.5;
    db.commit(store.write(new UnitOfWork(), w));

    var r = store.load();
    var j = r.person(jake.id);
    assertThat(j.mood).isEqualTo(w.person(jake.id).mood);
    assertThat(j.stateAt).isEqualTo(r.time); // state written ahead of the world is clamped to it
    assertThat(r.edgeOrNull(e.from, e.to).affinity).isEqualTo(-42.0);
    assertThat(r.edgeOrNull(e.from, e.to).pending).containsKey("finds_funny");
    assertThat(r.activeTags(jake.id, r.time, LIB)).contains("budget_tight");
    assertThat(r.knows("ARC", w.arcs.firstKey(), jake.id)).isTrue();
    assertThat(r.time).isEqualTo(w.time);
    assertThat(r.tension).isEqualTo(12.5);
  }
}
