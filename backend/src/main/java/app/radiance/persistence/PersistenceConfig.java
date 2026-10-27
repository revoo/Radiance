package app.radiance.persistence;

import app.radiance.kernel.WallClock;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PersistenceConfig {

  @Bean(destroyMethod = "close")
  WriterLane writerLane() {
    return new WriterLane("writer-lane");
  }

  @Bean
  LeaseHolder leaseHolder() {
    return new LeaseHolder();
  }

  /** Opens the database and applies pending migrations before anything else can touch it. */
  @Bean(destroyMethod = "close")
  JdbcSql jdbcSql(PersistenceProperties props, WriterLane lane, LeaseHolder holder, WallClock clock) {
    var sql = JdbcSql.open(Path.of(props.dbPath()), props.readPoolSize(), lane, holder);
    MigrationRunner.fromClasspath(sql, sql, clock).migrate();
    return sql;
  }

  @Bean
  WriterLease writerLease(JdbcSql sql, LeaseHolder holder, WallClock clock, PersistenceProperties props) {
    return new WriterLease(sql, sql, holder, clock, ownerId("app"), props.leaseTtl());
  }

  @Bean
  LeaseKeeper leaseKeeper(WriterLease lease, LeaseHolder holder, PersistenceProperties props) {
    return new LeaseKeeper(lease, holder, props.leaseRenewInterval());
  }

  /** Unique per process, readable in the Director's console: {@code app@host:pid:abcd1234}. */
  static String ownerId(String role) {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      host = "unknown";
    }
    return role + "@" + host + ":" + ProcessHandle.current().pid() + ":"
        + UUID.randomUUID().toString().substring(0, 8);
  }
}
