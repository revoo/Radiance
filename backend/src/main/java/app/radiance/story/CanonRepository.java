package app.radiance.story;

import static app.radiance.persistence.Table.col;

import app.radiance.kernel.Visibility;
import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.Collection;
import java.util.List;

/** Entities, facts and knowledge: the canon the engine keeps in memory. */
public final class CanonRepository {

  static final Table<EntityRow> ENTITIES =
      new Table<>(
          "entity",
          List.of("id"),
          r ->
              new EntityRow(
                  r.str("id"),
                  r.str("owner_id"),
                  r.enm("kind", EntityRow.Kind.class),
                  r.str("name"),
                  r.str("visual_description"),
                  r.lngOrNull("image_seed"),
                  r.str("attributes_json"),
                  r.str("status"),
                  r.lng("created_at"),
                  r.lng("updated_at")),
          col("id", EntityRow::id),
          col("owner_id", EntityRow::ownerId),
          col("kind", EntityRow::kind),
          col("name", EntityRow::name),
          col("visual_description", EntityRow::visualDescription),
          col("image_seed", EntityRow::imageSeed),
          col("attributes_json", EntityRow::attributesJson),
          col("status", EntityRow::status),
          col("created_at", EntityRow::createdAt),
          col("updated_at", EntityRow::updatedAt));

  static final Table<FactRow> FACTS =
      new Table<>(
          "fact",
          List.of("id"),
          r ->
              new FactRow(
                  r.str("id"),
                  r.str("subject_id"),
                  r.str("statement"),
                  r.enm("weight", FactRow.Weight.class),
                  r.enm("source", FactRow.Source.class),
                  r.enm("status", FactRow.Status.class),
                  r.enm("visibility", Visibility.class),
                  r.str("arc_id"),
                  r.lng("valid_from"),
                  r.lngOrNull("valid_to"),
                  r.str("origin_ref")),
          col("id", FactRow::id),
          col("subject_id", FactRow::subjectId),
          col("statement", FactRow::statement),
          col("weight", FactRow::weight),
          col("source", FactRow::source),
          col("status", FactRow::status),
          col("visibility", FactRow::visibility),
          col("arc_id", FactRow::arcId),
          col("valid_from", FactRow::validFrom),
          col("valid_to", FactRow::validTo),
          col("origin_ref", FactRow::originRef));

  static final Table<KnowledgeRow> KNOWLEDGE =
      new Table<>(
          "knowledge",
          List.of("subject_type", "subject_id", "actor_id"),
          r ->
              new KnowledgeRow(
                  r.enm("subject_type", KnowledgeRow.Subject.class),
                  r.str("subject_id"),
                  r.str("actor_id"),
                  r.lng("since")),
          col("subject_type", KnowledgeRow::subjectType),
          col("subject_id", KnowledgeRow::subjectId),
          col("actor_id", KnowledgeRow::actorId),
          col("since", KnowledgeRow::since));

  private final Sql sql;

  public CanonRepository(Sql sql) {
    this.sql = sql;
  }

  public List<EntityRow> entities() {
    return ENTITIES.all(sql);
  }

  /** Facts that aren't void; facts are never deleted (SPEC §5.10). */
  public List<FactRow> liveFacts() {
    return FACTS.where(sql, "status <> ?", FactRow.Status.VOID);
  }

  public List<KnowledgeRow> knowledge() {
    return KNOWLEDGE.all(sql);
  }

  public UnitOfWork saveEntities(UnitOfWork uow, Collection<EntityRow> rows) {
    return ENTITIES.upsert(uow, rows);
  }

  public UnitOfWork saveFacts(UnitOfWork uow, Collection<FactRow> rows) {
    return FACTS.upsert(uow, rows);
  }

  public UnitOfWork saveKnowledge(UnitOfWork uow, Collection<KnowledgeRow> rows) {
    return KNOWLEDGE.upsert(uow, rows);
  }
}
