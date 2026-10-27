package app.radiance.cast;

/** A resident (BOT) or R (USER). */
public record ActorRow(
    String id,
    Kind kind,
    String handle,
    String displayName,
    String avatarMediaId,
    String coverMediaId,
    String birthDate,
    String gender,
    String occupation,
    String employer,
    String hometown,
    String currentCity,
    String relationshipStatus,
    String bio,
    String circlesJson,
    long createdAt) {

  public enum Kind {
    BOT,
    USER
  }
}
