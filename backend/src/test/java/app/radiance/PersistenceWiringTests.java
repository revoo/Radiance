package app.radiance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.radiance.persistence.LeaseHolder;
import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import app.radiance.world.WorldRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** On startup the app migrates, creates the placeholder world row and takes the writer lease. */
@SpringBootTest
@ActiveProfiles({"offline", "test"})
class PersistenceWiringTests {

  @Autowired Sql sql;
  @Autowired WriterLane lane;
  @Autowired LeaseHolder holder;

  @Test
  void bootsIntoAWritableWorld() throws InterruptedException {
    for (int i = 0; i < 100 && !holder.isHeld(); i++) Thread.sleep(50);
    assertThat(holder.isHeld()).as("writer lease acquired").isTrue();

    var world = new WorldRepository(sql).load().orElseThrow();
    assertThat(world.networkName()).isEqualTo("Radiance");
    assertThat(world.timezone()).isEqualTo("America/Chicago");

    var uow = new UnitOfWork()
        .add("INSERT INTO session_log (id, started_at) VALUES (?, ?)", "s1", 1L);
    assertThatThrownBy(() -> sql.commit(uow)).hasMessageContaining("writer lane");
    lane.run(() -> sql.commit(uow));
    assertThat(sql.query("SELECT id FROM session_log", List.of(), r -> r.str("id")))
        .containsExactly("s1");
  }
}
