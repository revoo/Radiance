package app.radiance.persistence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param dbPath the SQLite file
 * @param readPoolSize connections for API reads
 * @param leaseTtl how long the writer lease lasts without renewal (a crashed process blocks writes
 *     for at most this long)
 * @param leaseRenewInterval how often the lease is renewed or retried; well under the TTL
 */
@ConfigurationProperties("radiance.persistence")
public record PersistenceProperties(
    String dbPath, int readPoolSize, Duration leaseTtl, Duration leaseRenewInterval) {}
