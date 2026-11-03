package app.radiance.sim;

import app.radiance.content.ContentLibrary;
import app.radiance.sim.model.SimWorld;
import app.radiance.sim.model.Thing;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a scripted photo shows (SPEC §5.13). An entity's description and seed are reused verbatim, so Jesse's car is the
 * same car in every post. Outdoor shots carry the day's weather. Pure: no I/O, and the same inputs give the same prompt.
 */
public final class PhotoPrompts {

  /** The picture to ask for, and which entity it shows, if any. */
  public record Photo(String prompt, long seed, String entityId) {}

  private static final String ENTITY = "{entity.visual_description}";
  /** The look, and the rules: no identifiable faces except avatars, no children, no real people, no logos or text. */
  static final String RULES = ". Amateur smartphone photo, natural light, slightly imperfect framing."
      + " No identifiable faces, no children, no real people, no logos, no text.";
  private static final int CAPTION_CHARS = 400;
  private static final Pattern OUTDOORS = Pattern.compile(
      "(?i)\\b(street|outdoor|outside|patio|park|path|lakefront|skyline|driveway|bridge|river|rooftops?|porch|parking|tow truck)\\b");

  private PhotoPrompts() {}

  /**
   * The prompt for a scripted photo post. Empty when it names an entity the author doesn't have (it may have been sold),
   * or there's nothing to go on: the post just airs without a photo.
   *
   * @param sky the weather in plain words at the photo's time, or null
   */
  public static Optional<Photo> of(SimWorld w, ContentLibrary lib, String postId, String authorId, String arcId, Brief b, String sky) {
    return of(w, lib, postId, authorId, arcId, b, sky, null);
  }

  /**
   * The same, once the post is written: the picture shows what the caption says, so the salmon in the post is salmon in the
   * photo. An entity keeps its description too, so it still looks like itself.
   */
  public static Optional<Photo> of(SimWorld w, ContentLibrary lib, String postId, String authorId, String arcId, Brief b, String sky, String caption) {
    String template = b.extra().get("media") instanceof String s && !s.isBlank() ? s : b.text();
    if (template == null || template.isBlank()) return Optional.empty();
    Thing thing = null;
    if (template.contains(ENTITY)) {
      thing = thingFor(w, lib, authorId, b.defId(), arcId).orElse(null);
      if (thing == null || thing.visualDescription == null) return Optional.empty();
    }
    String scene = thing == null ? template : template.replace(ENTITY, thing.visualDescription);
    scene = scene.replaceAll("(?i)^amateur smartphone photo of ", "");
    String words = caption == null ? "" : caption.replaceAll("@[\\w.]+", "a friend").strip();
    if (words.length() > CAPTION_CHARS) words = words.substring(0, CAPTION_CHARS);
    boolean outdoors = OUTDOORS.matcher(scene + " " + words).find();
    if (!words.isEmpty()) {
      scene = (thing == null ? "" : scene + ". ") + "The photo someone posted with this caption: \"" + words
          + "\" Show what the caption describes, as they'd really have snapped it";
    }
    if (sky != null && outdoors) scene += ", in this weather: " + sky;
    long seed = thing == null ? postId.hashCode() : thing.imageSeed != null ? thing.imageSeed : thing.id.hashCode();
    return Optional.of(new Photo(scene + RULES, seed, thing == null ? null : thing.id));
  }

  /** The brief's media line in plain words, for the writers: they must never see a raw placeholder. */
  public static String forWriters(SimWorld w, ContentLibrary lib, String authorId, String defId, String arcId, String template) {
    if (template == null || !template.contains(ENTITY)) return template;
    var thing = thingFor(w, lib, authorId, defId, arcId);
    return template.replace(ENTITY, thing.map(t -> t.visualDescription).orElse("something of theirs"));
  }

  /** The thing a def is about: an arc's own entity, else the newest one the author has of the kind the def needs. */
  static Optional<Thing> thingFor(SimWorld w, ContentLibrary lib, String authorId, String defId, String arcId) {
    if (arcId != null && w.arcs.get(arcId) != null && w.arcs.get(arcId).data.get("entity") instanceof String id
        && w.things.containsKey(id)) {
      return Optional.of(w.things.get(id));
    }
    var mine = w.things.values().stream().filter(t -> t.ownerId.equals(authorId) && !"SOLD".equals(t.status))
        .sorted(Comparator.comparingLong((Thing t) -> -t.createdAt).thenComparing(t -> t.id)).toList();
    var kinds = new java.util.LinkedHashSet<String>();
    var event = defId == null ? null : lib.events().get(defId);
    if (event != null && event.requires() != null) event.requires().entities().forEach(k -> kinds.add(k.name()));
    var arc = defId == null ? null : lib.arcs().get(defId);
    if (arc != null) {
      for (var role : arc.roles().values()) {
        if (role.requires().get("has_entity") != null) kinds.add(String.valueOf(role.requires().get("has_entity")));
      }
      for (var phase : arc.phases()) {
        if (phase.entryEvent() != null && phase.entryEvent().createsEntity() != null) kinds.add(phase.entryEvent().createsEntity().kind().name());
      }
    }
    if (kinds.isEmpty()) return mine.stream().findFirst();
    return mine.stream().filter(t -> kinds.contains(t.kind.name())).findFirst();
  }
}
