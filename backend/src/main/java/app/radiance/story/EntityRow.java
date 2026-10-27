package app.radiance.story;

/** A story-relevant thing — the car, the house, the dog — with its canonical look (SPEC §5.5). */
public record EntityRow(
    String id,
    String ownerId,
    Kind kind,
    String name,
    String visualDescription,
    Long imageSeed,
    String attributesJson,
    String status,
    long createdAt,
    long updatedAt) {

  public enum Kind {
    CAR,
    HOME,
    PET,
    PROJECT,
    PLACE,
    BUSINESS,
    OBJECT
  }
}
