package app.radiance.batch;

import app.radiance.content.ContentLibrary;
import app.radiance.kernel.WallClock;
import app.radiance.llm.LlmGateway;
import app.radiance.persistence.LeaseHolder;
import app.radiance.persistence.Sql;
import app.radiance.persistence.SqlAdmin;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.ScriptWriter;
import app.radiance.sim.WorldEngine;
import app.radiance.world.WorldProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The batch in the app (SPEC §5.1): the {@link NightlyBatch}, its in-process {@link BatchScheduler}
 * (off in one-shot run modes, where the command drives the batch itself), and the first batch after
 * genesis imports a world.
 */
@Configuration(proxyBeanMethods = false)
class BatchConfig {

  @Bean
  NightlyBatch nightlyBatch(Sql sql, SqlAdmin admin, WriterLane lane, LeaseHolder lease, WallClock clock, ContentLibrary lib,
      WorldEngine engine, ScriptWriter writer, LlmGateway gateway, WorldProperties world,
      org.springframework.core.env.Environment env,
      @Value("${radiance.content.softening-check:true}") boolean softening) {
    // a YAML list, so bind it rather than @Value it (SPEC §9: UTC Unix crons)
    List<String> crons = org.springframework.boot.context.properties.bind.Binder.get(env)
        .bind("radiance.batch.crons", org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
        .orElse(List.of("0 9 * * *", "0 11 * * *"));
    return new NightlyBatch(sql, admin, lane, lease, clock, lib, engine, writer, gateway, world.networkName(), crons,
        softening);
  }

  @Bean
  BatchScheduler batchScheduler(NightlyBatch batch, Sql sql, LeaseHolder lease, WallClock clock,
      @Value("${radiance.batch.enabled:true}") boolean enabled) {
    // one-shot run modes (genesis, batch, bake-off) drive the batch themselves, or not at all
    boolean oneShot = System.getProperty("radiance.mode") != null;
    return new BatchScheduler(batch, sql, lease, clock, enabled && !oneShot);
  }
}
