package app.radiance.llm;

import static app.radiance.llm.GatewayFixtures.model;
import static app.radiance.llm.GatewayFixtures.props;
import static app.radiance.llm.GatewayFixtures.provider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteTableTests {

  @Test
  void aRouteWhoseModelLacksVisionFailsStartup() {
    var base = props(LlmProperties.Mode.LIVE, Map.of());
    var models = new HashMap<>(base.models());
    models.put("blind", model("a", false, 100_000, 1));
    var props = new LlmProperties(base.mode(), base.providers(), models,
        new HashMap<>(Map.of()) {{
          putAll(base.routes());
          put("vision-appraisal", new LlmProperties.Route("blind", List.of()));
        }}, 24_000, null, false, null);
    assertThatThrownBy(() -> RouteTable.build(props))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("route 'vision-appraisal': model 'blind' lacks vision");
  }

  @Test
  void unknownAliasesProvidersAndPurposesAreAllReported() {
    var base = props(LlmProperties.Mode.LIVE, Map.of());
    var routes = new HashMap<>(base.routes());
    routes.put("summary", new LlmProperties.Route("nope", List.of()));
    routes.put("not-a-purpose", new LlmProperties.Route("m1", List.of()));
    routes.remove("recap");
    var models = new HashMap<>(base.models());
    models.put("orphan", model("ghost", false, 1000, 1));
    routes.put("compaction", new LlmProperties.Route("orphan", List.of()));
    var props = new LlmProperties(base.mode(), base.providers(), models, routes, 0, null, false, null);
    assertThatThrownBy(() -> RouteTable.build(props))
        .hasMessageContaining("route 'summary': unknown model alias 'nope'")
        .hasMessageContaining("route 'not-a-purpose': not a purpose")
        .hasMessageContaining("route 'recap': missing")
        .hasMessageContaining("model 'orphan': unknown provider 'ghost'");
  }

  @Test
  void keylessModelsAreSkippedAndAnEmptyRouteFallsBackToFake() {
    var base = props(LlmProperties.Mode.LIVE, Map.of());
    var providers = Map.of("a", provider(""), "b", provider(null));
    var props = new LlmProperties(base.mode(), providers, base.models(), base.routes(), 0, null,
        false, null);
    var table = RouteTable.build(props);
    assertThat(table.targets(Purpose.RENDER_THREAD)).extracting(RouteTable.Target::alias)
        .as("b needs no key (a local runner); a's key is missing").containsExactly("m2");

    var none = new LlmProperties(base.mode(), Map.of("a", provider(""), "b", provider("")),
        base.models(), base.routes(), 0, null, false, null);
    assertThat(RouteTable.build(none).targets(Purpose.GENESIS)).containsExactly(RouteTable.FAKE);
  }

  @Test
  void degradationRungsEngageFromEightyPercent() {
    assertThat(Degradation.level(0.79)).isZero();
    assertThat(Degradation.level(0.80)).isEqualTo(1);
    assertThat(Degradation.level(0.90)).isEqualTo(4);
    assertThat(Degradation.level(0.999)).isEqualTo(6);
    assertThat(Degradation.level(1.5)).isEqualTo(6);
  }
}
