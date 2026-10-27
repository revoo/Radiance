package app.radiance.world;

import static app.radiance.persistence.Table.col;

import app.radiance.persistence.Schema;
import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.List;
import java.util.Optional;

public final class WorldRepository {

  static final Table<WorldRow> TABLE =
      new Table<>(
          "world",
          List.of("id"),
          r ->
              new WorldRow(
                  r.str("id"),
                  r.str("name"),
                  r.str("network_name"),
                  r.str("timezone"),
                  r.lng("seed"),
                  r.lng("committed_until"),
                  r.lng("scripted_until"),
                  r.lng("time_offset_ms"),
                  r.bool("paused"),
                  r.dbl("tension"),
                  r.lngOrNull("last_session_at"),
                  r.str("settings_json"),
                  r.lng("created_at")),
          col("id", WorldRow::id),
          col("name", WorldRow::name),
          col("network_name", WorldRow::networkName),
          col("timezone", WorldRow::timezone),
          col("seed", WorldRow::seed),
          col("committed_until", WorldRow::committedUntil),
          col("scripted_until", WorldRow::scriptedUntil),
          col("time_offset_ms", WorldRow::timeOffsetMs),
          col("paused", WorldRow::paused),
          col("tension", WorldRow::tension),
          col("last_session_at", WorldRow::lastSessionAt),
          col("settings_json", WorldRow::settingsJson),
          col("created_at", WorldRow::createdAt));

  private final Sql sql;

  public WorldRepository(Sql sql) {
    this.sql = sql;
  }

  public Optional<WorldRow> load() {
    return TABLE.one(sql, "id = ?", Schema.WORLD_ID);
  }

  public UnitOfWork save(UnitOfWork uow, WorldRow world) {
    return TABLE.upsert(uow, world);
  }

  /** The bootstrap row: committed and scripted up to now, so nothing is due yet. */
  static UnitOfWork insertPlaceholder(WorldProperties props, long nowMs) {
    var row =
        new WorldRow(
            Schema.WORLD_ID,
            props.name(),
            props.networkName(),
            props.timezone(),
            props.seed(),
            nowMs,
            nowMs,
            0,
            false,
            0,
            null,
            "{}",
            nowMs);
    return TABLE.insert(new UnitOfWork(), List.of(row));
  }
}
