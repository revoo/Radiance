package app.radiance.persistence;

import app.radiance.kernel.ManualClock;
import java.nio.file.Path;

class JdbcSqlContractTests extends SqlContractTests {

  @Override
  TestDatabase open(Path file, String owner, ManualClock clock) {
    return TestDatabase.sqlite(file, owner, clock);
  }
}
