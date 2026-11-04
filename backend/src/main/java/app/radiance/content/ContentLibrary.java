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

/**
 * Every content file, loaded and cross-validated as one library (ADR 0015). Immutable but for the blacklist, which the
 * Director can add to while the world runs (SPEC §5.16).
 */
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
    InnerLives innerLives,
    Map<String, List<Map<String, Object>>> tables,
    Map<String, String> prompts,
    List<ContentProperties.BlacklistEntry> blacklist) {

  public ContentLibrary {
    blacklist = new java.util.concurrent.CopyOnWriteArrayList<>(blacklist);
  }

  /** Stops an arc from starting, from now on. */
  public void block(String arcId) {
    if (!isBlacklisted(arcId)) blacklist.add(new ContentProperties.BlacklistEntry(arcId, List.of()));
  }

  /** Lets an arc start again. */
  public void unblock(String arcId) {
    blacklist.removeIf(b -> b.id().equals(arcId));
  }

  public boolean isBlacklisted(String arcId) {
    return blacklist.stream().anyMatch(b -> b.id().equals(arcId));
  }

  /** A curated table ({@code tables/<name>.yaml}): rows the engine draws from. */
  public List<Map<String, Object>> table(String name) {
    var t = tables.get(name);
    if (t == null) throw new IllegalArgumentException("no content table '" + name + "'");
    return t;
  }

  /** A prompt template ({@code prompts/<name>.md}). */
  public String prompt(String name) {
    var p = prompts.get(name);
    if (p == null) throw new IllegalArgumentException("no prompt template '" + name + "'");
    return p;
  }

  public ThemeLinter themeLinter() {
    return new ThemeLinter(blacklist);
  }

  /** Arcs that may start: everything not blacklisted. */
  public Map<String, ArcDef> startableArcs() {
    var out = new java.util.TreeMap<String, ArcDef>();
    arcs.forEach((k, v) -> {
      if (!isBlacklisted(k)) out.put(k, v);
    });
    return java.util.Collections.unmodifiableMap(out);
  }
}
