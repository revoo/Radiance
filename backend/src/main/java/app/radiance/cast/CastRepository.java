package app.radiance.cast;

import static app.radiance.persistence.Table.col;

import app.radiance.persistence.Sql;
import app.radiance.persistence.Table;
import app.radiance.persistence.UnitOfWork;
import java.util.Collection;
import java.util.List;

/** Actors, personas and actor state: the cast aggregate, loaded whole into the world model. */
public final class CastRepository {

  static final Table<ActorRow> ACTORS =
      new Table<>(
          "actor",
          List.of("id"),
          r ->
              new ActorRow(
                  r.str("id"),
                  r.enm("kind", ActorRow.Kind.class),
                  r.str("handle"),
                  r.str("display_name"),
                  r.str("avatar_media_id"),
                  r.str("cover_media_id"),
                  r.str("birth_date"),
                  r.str("gender"),
                  r.str("occupation"),
                  r.str("employer"),
                  r.str("hometown"),
                  r.str("current_city"),
                  r.str("relationship_status"),
                  r.str("bio"),
                  r.str("circles_json"),
                  r.lng("created_at")),
          col("id", ActorRow::id),
          col("kind", ActorRow::kind),
          col("handle", ActorRow::handle),
          col("display_name", ActorRow::displayName),
          col("avatar_media_id", ActorRow::avatarMediaId),
          col("cover_media_id", ActorRow::coverMediaId),
          col("birth_date", ActorRow::birthDate),
          col("gender", ActorRow::gender),
          col("occupation", ActorRow::occupation),
          col("employer", ActorRow::employer),
          col("hometown", ActorRow::hometown),
          col("current_city", ActorRow::currentCity),
          col("relationship_status", ActorRow::relationshipStatus),
          col("bio", ActorRow::bio),
          col("circles_json", ActorRow::circlesJson),
          col("created_at", ActorRow::createdAt));

  static final Table<PersonaRow> PERSONAS =
      new Table<>(
          "persona",
          List.of("actor_id"),
          r ->
              new PersonaRow(
                  r.str("actor_id"),
                  r.str("traits_json"),
                  r.str("quirks_json"),
                  r.str("interests_json"),
                  r.str("politics_json"),
                  r.str("biases_json"),
                  r.str("taste_json"),
                  r.str("temperament_json"),
                  r.str("voice_json"),
                  r.str("routine_json"),
                  r.str("values_json"),
                  r.str("fears_json"),
                  r.str("desires_json"),
                  r.str("off_network_json"),
                  r.str("identity_json"),
                  r.str("backstory"),
                  r.str("life_summary"),
                  r.bool("pinned")),
          col("actor_id", PersonaRow::actorId),
          col("traits_json", PersonaRow::traitsJson),
          col("quirks_json", PersonaRow::quirksJson),
          col("interests_json", PersonaRow::interestsJson),
          col("politics_json", PersonaRow::politicsJson),
          col("biases_json", PersonaRow::biasesJson),
          col("taste_json", PersonaRow::tasteJson),
          col("temperament_json", PersonaRow::temperamentJson),
          col("voice_json", PersonaRow::voiceJson),
          col("routine_json", PersonaRow::routineJson),
          col("values_json", PersonaRow::valuesJson),
          col("fears_json", PersonaRow::fearsJson),
          col("desires_json", PersonaRow::desiresJson),
          col("off_network_json", PersonaRow::offNetworkJson),
          col("identity_json", PersonaRow::identityJson),
          col("backstory", PersonaRow::backstory),
          col("life_summary", PersonaRow::lifeSummary),
          col("pinned", PersonaRow::pinned));

  static final Table<ActorStateRow> STATES =
      new Table<>(
          "actor_state",
          List.of("actor_id"),
          r ->
              new ActorStateRow(
                  r.str("actor_id"),
                  r.dbl("mood"),
                  r.dbl("energy"),
                  r.dbl("stress"),
                  r.dblOrNull("partner_satisfaction"),
                  r.str("trait_offsets_json"),
                  r.str("location"),
                  r.lng("interactions_today"),
                  r.lngOrNull("last_feed_check_at"),
                  r.lng("updated_at")),
          col("actor_id", ActorStateRow::actorId),
          col("mood", ActorStateRow::mood),
          col("energy", ActorStateRow::energy),
          col("stress", ActorStateRow::stress),
          col("partner_satisfaction", ActorStateRow::partnerSatisfaction),
          col("trait_offsets_json", ActorStateRow::traitOffsetsJson),
          col("location", ActorStateRow::location),
          col("interactions_today", ActorStateRow::interactionsToday),
          col("last_feed_check_at", ActorStateRow::lastFeedCheckAt),
          col("updated_at", ActorStateRow::updatedAt));

  private final Sql sql;

  public CastRepository(Sql sql) {
    this.sql = sql;
  }

  public List<ActorRow> actors() {
    return ACTORS.all(sql);
  }

  public List<PersonaRow> personas() {
    return PERSONAS.all(sql);
  }

  public List<ActorStateRow> states() {
    return STATES.all(sql);
  }

  public UnitOfWork saveActors(UnitOfWork uow, Collection<ActorRow> rows) {
    return ACTORS.upsert(uow, rows);
  }

  public UnitOfWork savePersonas(UnitOfWork uow, Collection<PersonaRow> rows) {
    return PERSONAS.upsert(uow, rows);
  }

  public UnitOfWork saveStates(UnitOfWork uow, Collection<ActorStateRow> rows) {
    return STATES.upsert(uow, rows);
  }
}
