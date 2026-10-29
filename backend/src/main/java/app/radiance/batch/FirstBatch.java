package app.radiance.batch;

import app.radiance.world.GenesisImported;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Genesis ends with a first batch: the history backfill and the first script (SPEC §6.4 step 8). */
@Component
class FirstBatch {

  private static final Logger log = LoggerFactory.getLogger(FirstBatch.class);

  private final NightlyBatch batch;

  FirstBatch(NightlyBatch batch) {
    this.batch = batch;
  }

  @EventListener
  void onGenesis(GenesisImported event) {
    log.info("Genesis imported seed {} ({} residents); running the first batch", event.seed(), event.residents());
    var report = batch.run(NightlyBatch.Kind.BACKFILL);
    log.info("First batch: {} ({} planned, {} rendered) {}", report.status(), report.planned(), report.rendered(),
        report.notes());
  }
}
