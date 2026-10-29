package app.radiance.live;

import app.radiance.batch.NightlyBatch;
import app.radiance.kernel.WallClock;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.LlmGateway;
import app.radiance.persistence.LeaseHolder;
import app.radiance.persistence.Sql;
import app.radiance.persistence.WriterLane;
import app.radiance.sim.UserActions;
import app.radiance.sim.WorldEngine;
import app.radiance.world.WorldProperties;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Live play for the web app (SPEC §5.1); one-shot run modes have no sessions. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class LiveConfig {

  @Bean
  UserActions userActions(Sql sql, WriterLane lane, WorldEngine engine) {
    return new UserActions(sql, lane, engine);
  }

  @Bean
  Appraiser appraiser(LlmGateway gateway, FakeResponses fakes) {
    Appraiser.registerFake(fakes);
    return new Appraiser(gateway, Duration.ofSeconds(8));
  }

  @Bean(destroyMethod = "stop")
  LivePlay livePlay(Sql sql, WriterLane lane, WorldEngine engine, UserActions actions, Appraiser appraiser,
      LlmGateway gateway, WallClock clock, LeaseHolder lease, NightlyBatch batch, WorldProperties world,
      @Value("${radiance.content.softening-check:true}") boolean softening,
      @Value("${radiance.session.idle-timeout:10m}") Duration idleTimeout) {
    var backfill = new LivePlay.Backfill() {
      @Override
      public boolean running() {
        return batch.isRunning();
      }

      @Override
      public void start() {
        Thread.ofVirtual().name("live-backfill").start(() -> batch.run(NightlyBatch.Kind.BACKFILL));
      }
    };
    return new LivePlay(sql, lane, engine, actions, appraiser, gateway, clock, lease, backfill, world.networkName(),
        softening, idleTimeout);
  }
}
