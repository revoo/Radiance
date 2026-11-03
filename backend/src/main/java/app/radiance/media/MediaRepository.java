package app.radiance.media;

import static app.radiance.persistence.Table.col;

import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.List;
import java.util.Optional;

public final class MediaRepository {

  private static Integer integer(Long v) {
    return v == null ? null : v.intValue();
  }

  static final Table<MediaRow> TABLE =
      new Table<>(
          "media",
          List.of("id"),
          r -> new MediaRow(r.str("id"), r.str("kind"), r.str("origin"), r.str("storage_key"), r.str("thumb_key"), r.str("mime"),
              integer(r.lngOrNull("width")), integer(r.lngOrNull("height")), r.lngOrNull("bytes"), r.str("prompt"),
              r.lngOrNull("seed"), r.str("entity_id"), r.str("description"), r.str("status"), r.str("appraisal_json"), r.lng("created_at")),
          col("id", MediaRow::id),
          col("kind", MediaRow::kind),
          col("origin", MediaRow::origin),
          col("storage_key", MediaRow::storageKey),
          col("thumb_key", MediaRow::thumbKey),
          col("mime", MediaRow::mime),
          col("width", MediaRow::width),
          col("height", MediaRow::height),
          col("bytes", MediaRow::bytes),
          col("prompt", MediaRow::prompt),
          col("seed", MediaRow::seed),
          col("entity_id", MediaRow::entityId),
          col("description", MediaRow::description),
          col("status", MediaRow::status),
          col("appraisal_json", MediaRow::appraisalJson),
          col("created_at", MediaRow::createdAt));

  private final Sql sql;

  public MediaRepository(Sql sql) {
    this.sql = sql;
  }

  public Optional<MediaRow> byId(String id) {
    return TABLE.one(sql, "id = ?", id);
  }

  /** Generated images since {@code from}, for the daily budgets (SPEC §5.13). */
  public long generatedSince(long from) {
    return sql.queryOne("SELECT COUNT(*) AS n FROM media WHERE origin = 'GENERATED' AND status = 'READY' AND created_at >= ?",
        List.of(from), r -> r.lng("n")).orElse(0L);
  }

  public UnitOfWork save(UnitOfWork uow, MediaRow row) {
    return TABLE.upsert(uow, row);
  }
}
