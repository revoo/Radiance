package app.radiance.batch;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.persistence.Sql;
import app.radiance.world.genesis.GenesisService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Genesis ends with a first batch (SPEC §6.4 steps 5 and 8): the import starts the world two weeks
 * back, and the batch backfills those days and writes the first script, so the first login has content.
 */
@SpringBootTest(properties = "radiance.genesis.seed-file=${java.io.tmpdir}/radiance-test/first-batch-seed.json")
@ActiveProfiles({"offline", "test"})
class FirstBatchTests {

  @Autowired GenesisService genesis;
  @Autowired Sql sql;

  @Test
  void importingAWorldRunsTheFirstBatch() throws Exception {
    genesis.preview(7, true, false);
    genesis.commit();
    var runs = sql.query("SELECT kind, status, items_planned FROM batch_run", List.of(),
        r -> r.str("kind") + " " + r.str("status") + " " + r.lng("items_planned"));
    assertThat(runs).hasSize(1).first().asString().startsWith("BACKFILL DONE");
    long backfilled = sql.queryOne("SELECT COUNT(*) AS n FROM post WHERE origin = 'BACKFILL' AND body IS NOT NULL",
        List.of(), r -> r.lng("n")).orElseThrow();
    long scripted = sql.queryOne("SELECT COUNT(*) AS n FROM post WHERE origin = 'SCRIPT'", List.of(), r -> r.lng("n"))
        .orElseThrow();
    assertThat(backfilled).as("two weeks of history").isGreaterThan(20);
    assertThat(scripted).as("the first script").isPositive();
  }
}
