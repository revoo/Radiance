package app.radiance.world;

import app.radiance.kernel.WallClock;
import app.radiance.persistence.SqlAdmin;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Makes sure the single world row exists, since the writer lease lives on it (ADR 0012). It is a
 * placeholder until genesis fills it in; an existing row is never touched.
 */
@Component
class WorldRowBootstrap implements InitializingBean {

  private final SqlAdmin admin;
  private final WorldProperties props;
  private final WallClock clock;

  WorldRowBootstrap(SqlAdmin admin, WorldProperties props, WallClock clock) {
    this.admin = admin;
    this.props = props;
    this.clock = clock;
  }

  @Override
  public void afterPropertiesSet() {
    admin.execute(WorldRepository.insertPlaceholder(props, clock.nowMs()));
  }
}
