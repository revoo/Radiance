package app.radiance.content;

import app.radiance.content.Defs.ArcDef;
import app.radiance.content.Defs.ConstraintDef;
import app.radiance.content.Defs.EventDef;
import app.radiance.content.Defs.QuirkDef;
import app.radiance.content.Rules.EngagementRules;
import app.radiance.content.Rules.PacingRules;
import app.radiance.content.Rules.RelationshipRules;
import app.radiance.content.Rules.TemperamentRules;
import app.radiance.content.Rules.WeatherCondition;
import java.util.List;
import java.util.Map;

/** Every content file, loaded and cross-validated as one library (ADR 0015). Immutable. */
public record ContentLibrary(
    Map<String, QuirkDef> quirks,
    Map<String, EventDef> events,
    Map<String, ArcDef> arcs,
    Map<String, ConstraintDef> constraints,
    TemperamentRules temperament,
    Map<String, WeatherCondition> weather,
    RelationshipRules relationships,
    EngagementRules engagement,
    PacingRules pacing,
    Roster roster,
    Map<String, List<Map<String, Object>>> tables,
    List<ContentProperties.BlacklistEntry> blacklist) {

  public boolean isBlacklisted(String arcId) {
    return blacklist.stream().anyMatch(b -> b.id().equals(arcId));
  }

  /** A curated table ({@code tables/<name>.yaml}): rows the engine draws from. */
  public List<Map<String, Object>> table(String name) {
    var t = tables.get(name);
    if (t == null) throw new IllegalArgumentException("no content table '" + name + "'");
    return t;
  }

  public ThemeLinter themeLinter() {
    return new ThemeLinter(blacklist);
  }

  /** Arcs that may start: everything not blacklisted. */
  public Map<String, ArcDef> startableArcs() {
    return Map.copyOf(arcs.entrySet().stream()
        .filter(e -> !isBlacklisted(e.getKey()))
        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
  }
}
