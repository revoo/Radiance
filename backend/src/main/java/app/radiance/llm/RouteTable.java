package app.radiance.llm;

import app.radiance.llm.provider.FakeLlm;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Purpose → ordered models, built and checked once from configuration (SPEC §5.10). Startup fails if a
 * route names an unknown alias or provider, or a model that lacks a capability its purpose needs. In
 * LIVE mode, models whose provider has no key are skipped, and a route left empty is served by FakeLlm
 * with a warning. In FAKE mode every route is FakeLlm.
 */
public final class RouteTable {

  private static final Logger log = LoggerFactory.getLogger(RouteTable.class);

  public static final String FAKE_PROVIDER = "fake";

  /** One model on a route, with everything needed to call it. */
  public record Target(
      String alias, LlmProperties.Model model, String providerName, LlmProperties.Provider provider) {

    boolean isFake() {
      return FAKE_PROVIDER.equals(providerName);
    }
  }

  static final LlmProperties.Model FAKE_MODEL =
      new LlmProperties.Model(
          FAKE_PROVIDER,
          FakeLlm.ALIAS,
          new LlmProperties.Capabilities(
              LlmProperties.StructuredOutput.SCHEMA, true, LlmProperties.PromptCaching.NONE, 200_000),
          Map.of(),
          new LlmProperties.Pricing(0, 0, 0, 0),
          null);

  static final LlmProperties.Provider FAKE_PROVIDER_CONFIG =
      new LlmProperties.Provider(
          LlmProperties.Kind.OPENAI_COMPATIBLE, null, null, 64, null, null, null, null);

  static final Target FAKE = new Target(FakeLlm.ALIAS, FAKE_MODEL, FAKE_PROVIDER, FAKE_PROVIDER_CONFIG);

  private final Map<Purpose, List<Target>> routes;

  private RouteTable(Map<Purpose, List<Target>> routes) {
    this.routes = routes;
  }

  public List<Target> targets(Purpose purpose) {
    return routes.get(purpose);
  }

  /** The route reordered cheapest first: the ladder's last rung for live purposes (SPEC §5.15). */
  public List<Target> cheapestFirst(Purpose purpose) {
    return routes.get(purpose).stream()
        .sorted(Comparator.comparingDouble(t -> t.model().pricing().output()))
        .toList();
  }

  public static RouteTable build(LlmProperties props) {
    var errors = new ArrayList<String>();
    var routes = new EnumMap<Purpose, List<Target>>(Purpose.class);

    for (String key : props.routes().keySet()) {
      try {
        Purpose.fromKey(key);
      } catch (IllegalArgumentException e) {
        errors.add("route '" + key + "': not a purpose");
      }
    }

    for (Purpose purpose : Purpose.values()) {
      var route = props.routes().get(purpose.key());
      if (route == null) {
        if (props.mode() == LlmProperties.Mode.FAKE) {
          routes.put(purpose, List.of(FAKE));
        } else {
          errors.add("route '" + purpose.key() + "': missing");
        }
        continue;
      }
      var aliases = new ArrayList<String>();
      aliases.add(route.model());
      aliases.addAll(route.fallback());
      var targets = new ArrayList<Target>();
      for (String alias : aliases) {
        var model = props.models().get(alias);
        if (model == null) {
          errors.add("route '" + purpose.key() + "': unknown model alias '" + alias + "'");
          continue;
        }
        var provider = props.providers().get(model.provider());
        if (provider == null) {
          errors.add("model '" + alias + "': unknown provider '" + model.provider() + "'");
          continue;
        }
        if (purpose.needsVision() && !model.capabilities().vision()) {
          errors.add("route '" + purpose.key() + "': model '" + alias + "' lacks vision");
          continue;
        }
        if (props.mode() == LlmProperties.Mode.LIVE && !provider.hasKey()) {
          log.info("LLM: skipping '{}' on {}: provider '{}' has no API key",
              alias, purpose.key(), model.provider());
          continue;
        }
        targets.add(new Target(alias, model, model.provider(), provider));
      }
      if (props.mode() == LlmProperties.Mode.FAKE) {
        routes.put(purpose, List.of(FAKE));
      } else if (targets.isEmpty()) {
        log.warn("LLM: no usable model for '{}'; serving it with FakeLlm", purpose.key());
        routes.put(purpose, List.of(FAKE));
      } else {
        routes.put(purpose, List.copyOf(targets));
      }
    }

    if (!errors.isEmpty()) {
      throw new IllegalStateException("Invalid radiance.llm configuration:\n  - "
          + String.join("\n  - ", errors));
    }
    return new RouteTable(routes);
  }
}
