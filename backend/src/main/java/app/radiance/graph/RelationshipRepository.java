package app.radiance.graph;

import static app.radiance.persistence.Table.col;

import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.Collection;
import java.util.List;

public final class RelationshipRepository {

  static final Table<RelationshipRow> TABLE =
      new Table<>(
          "relationship",
          List.of("from_id", "to_id"),
          r ->
              new RelationshipRow(
                  r.str("from_id"),
                  r.str("to_id"),
                  r.enm("kind", RelationshipRow.Kind.class),
                  r.dbl("affinity"),
                  r.dbl("trust"),
                  r.dbl("attraction"),
                  r.dbl("familiarity"),
                  r.dbl("respect"),
                  r.dbl("chemistry"),
                  r.str("tags_json"),
                  r.str("summary"),
                  r.bool("connected"),
                  r.lngOrNull("last_interaction_at"),
                  r.lng("updated_at"),
                  r.str("pending_tags_json")),
          col("from_id", RelationshipRow::fromId),
          col("to_id", RelationshipRow::toId),
          col("kind", RelationshipRow::kind),
          col("affinity", RelationshipRow::affinity),
          col("trust", RelationshipRow::trust),
          col("attraction", RelationshipRow::attraction),
          col("familiarity", RelationshipRow::familiarity),
          col("respect", RelationshipRow::respect),
          col("chemistry", RelationshipRow::chemistry),
          col("tags_json", RelationshipRow::tagsJson),
          col("summary", RelationshipRow::summary),
          col("connected", RelationshipRow::connected),
          col("last_interaction_at", RelationshipRow::lastInteractionAt),
          col("updated_at", RelationshipRow::updatedAt),
          col("pending_tags_json", RelationshipRow::pendingTagsJson));

  private final Sql sql;

  public RelationshipRepository(Sql sql) {
    this.sql = sql;
  }

  public List<RelationshipRow> all() {
    return TABLE.all(sql);
  }

  /** How everyone sees {@code toId}: for R, the like-meters (SPEC §5.9). */
  public List<RelationshipRow> toward(String toId) {
    return TABLE.where(sql, "to_id = ?", toId);
  }

  public UnitOfWork save(UnitOfWork uow, Collection<RelationshipRow> rows) {
    return TABLE.upsert(uow, rows);
  }
}
