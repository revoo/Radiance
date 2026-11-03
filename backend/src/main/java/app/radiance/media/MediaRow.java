package app.radiance.media;

/**
 * A photo or avatar (SPEC §4.3). The bytes live in a {@link MediaStore}, never in the database.
 *
 * @param kind PHOTO or AVATAR
 * @param origin UPLOAD or GENERATED
 * @param description alt text: the generation prompt, or the vision appraisal's description
 * @param status PENDING, READY, DEFERRED or FAILED
 * @param appraisalJson what the vision appraisal made of an upload
 */
public record MediaRow(
    String id, String kind, String origin, String storageKey, String thumbKey, String mime, Integer width, Integer height,
    Long bytes, String prompt, Long seed, String entityId, String description, String status, String appraisalJson, long createdAt) {

  public static final String PENDING = "PENDING";
  public static final String READY = "READY";
  public static final String DEFERRED = "DEFERRED";
  public static final String FAILED = "FAILED";

  public MediaRow withStatus(String next) {
    return new MediaRow(id, kind, origin, storageKey, thumbKey, mime, width, height, bytes, prompt, seed, entityId, description,
        next, appraisalJson, createdAt);
  }

  public MediaRow ready(String key, String thumb, String type, long size) {
    return new MediaRow(id, kind, origin, key, thumb, type, width, height, size, prompt, seed, entityId, description, READY,
        appraisalJson, createdAt);
  }

  public MediaRow describedAs(String text, String appraisal) {
    return new MediaRow(id, kind, origin, storageKey, thumbKey, mime, width, height, bytes, prompt, seed, entityId, text, status,
        appraisal, createdAt);
  }
}
