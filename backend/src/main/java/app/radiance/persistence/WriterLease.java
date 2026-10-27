package app.radiance.persistence;

import app.radiance.kernel.WallClock;
import java.time.Duration;
import java.util.List;

/**
 * The fenced writer lease on the world row (SPEC §4.2). Acquiring takes over only an absent or
 * expired lease and increments the epoch, in one conditional UPDATE; a read-back tells us whether we
 * won. Lease times are wall-clock (ADR 0010). These updates go through {@link SqlAdmin}: they aren't
 * world-state mutations, and they're what grants the right to make them.
 */
public final class WriterLease {

  private final Sql sql;
  private final SqlAdmin admin;
  private final LeaseHolder holder;
  private final WallClock clock;
  private final String owner;
  private final long ttlMs;

  public WriterLease(
      Sql sql, SqlAdmin admin, LeaseHolder holder, WallClock clock, String owner, Duration ttl) {
    this.sql = sql;
    this.admin = admin;
    this.holder = holder;
    this.clock = clock;
    this.owner = owner;
    this.ttlMs = ttl.toMillis();
  }

  public String owner() {
    return owner;
  }

  /** Takes the lease if it is free or expired (or already ours). Returns whether we hold it. */
  public boolean tryAcquire() {
    long now = clock.nowMs();
    admin.execute(
        new UnitOfWork()
            .add(
                "UPDATE world SET lease_owner = ?, lease_until = ?, lease_epoch = lease_epoch + 1"
                    + " WHERE id = ? AND (lease_owner IS NULL OR lease_until IS NULL"
                    + " OR lease_until <= ?)",
                owner,
                now + ttlMs,
                Schema.WORLD_ID,
                now));
    return readBack();
  }

  /** Extends the lease we hold. Returns false (and forgets the lease) if it was taken over. */
  public boolean renew() {
    var fence = holder.current().orElse(null);
    if (fence == null) return false;
    admin.execute(
        new UnitOfWork()
            .add(
                "UPDATE world SET lease_until = ? WHERE id = ? AND lease_owner = ? AND lease_epoch = ?",
                clock.nowMs() + ttlMs,
                Schema.WORLD_ID,
                fence.owner(),
                fence.epoch()));
    if (readBack() && holder.current().filter(fence::equals).isPresent()) return true;
    holder.clearIf(fence);
    return false;
  }

  /** Gives the lease up so another process can take it at once. */
  public void release() {
    var fence = holder.current().orElse(null);
    if (fence == null) return;
    holder.clearIf(fence);
    admin.execute(
        new UnitOfWork()
            .add(
                "UPDATE world SET lease_owner = NULL, lease_until = NULL"
                    + " WHERE id = ? AND lease_owner = ? AND lease_epoch = ?",
                Schema.WORLD_ID,
                fence.owner(),
                fence.epoch()));
  }

  private boolean readBack() {
    var current =
        sql.queryOne(
            "SELECT lease_owner, lease_epoch FROM world WHERE id = ?",
            List.of(Schema.WORLD_ID),
            r -> new LeaseHolder.Fence(r.str("lease_owner"), r.lng("lease_epoch")));
    if (current.isPresent() && owner.equals(current.get().owner())) {
      holder.set(current.get());
      return true;
    }
    holder.clear();
    return false;
  }
}
