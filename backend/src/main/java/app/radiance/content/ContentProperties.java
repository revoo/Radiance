package app.radiance.content;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code radiance.content} (SPEC §9).
 *
 * @param edge how often mockery, hostility and prejudice surface (0–2)
 * @param strongPrejudice residents with real prejudice; applies at genesis
 * @param arcBlacklist arcs that never happen and whose themes stay out of prose (SPEC §5.5)
 */
@ConfigurationProperties("radiance.content")
public record ContentProperties(
    double edge,
    int strongPrejudice,
    boolean softeningCheck,
    boolean sensitiveArcs,
    List<BlacklistEntry> arcBlacklist) {

  public ContentProperties {
    arcBlacklist = arcBlacklist == null ? List.of() : List.copyOf(arcBlacklist);
  }

  public record BlacklistEntry(String id, List<String> themeTerms) {
    public BlacklistEntry {
      themeTerms = themeTerms == null ? List.of() : List.copyOf(themeTerms);
    }
  }
}
