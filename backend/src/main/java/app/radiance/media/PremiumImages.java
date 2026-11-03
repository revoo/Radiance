package app.radiance.media;

/**
 * The better, paid model for pictures people will keep seeing (ADR 0023).
 *
 * @param generator null when there's none to use
 * @param perRun how many a batch may ask it for; the rest get the everyday model
 */
public record PremiumImages(ImageGenerator generator, int perRun) {}
