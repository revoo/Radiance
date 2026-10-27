package app.radiance.llm;

import app.radiance.kernel.ManualClock;
import app.radiance.llm.provider.FakeLlm;
import app.radiance.llm.provider.LlmProvider;
import app.radiance.llm.provider.ProviderRequest;
import app.radiance.llm.provider.ProviderResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Two configured providers ({@code a}, {@code b}) with scripted answers, and a gateway over them. */
final class GatewayFixtures {

  record Probe(String answer, int n) {}

  static final String GOOD = "{\"answer\":\"hi\",\"n\":1}";

  /** Answers from a queue (the last answer repeats), recording every request. */
  static final class Scripted implements LlmProvider {
    final Deque<Function<ProviderRequest, ProviderResponse>> answers = new ArrayDeque<>();
    final List<ProviderRequest> requests = new ArrayList<>();
    Function<ProviderRequest, ProviderResponse> last = r -> ok(GOOD);

    Scripted then(ProviderResponse response) {
      answers.add(r -> response);
      return this;
    }

    Scripted always(ProviderResponse response) {
      answers.clear();
      last = r -> response;
      return this;
    }

    @Override
    public synchronized ProviderResponse complete(ProviderRequest request) {
      requests.add(request);
      var next = answers.isEmpty() ? last : answers.poll();
      if (answers.isEmpty()) last = next;
      return next.apply(request);
    }
  }

  static ProviderResponse ok(String text) {
    return ProviderResponse.ok(text, new ProviderResponse.TokenCounts(100, 0, 0, 20, 0), "model-x");
  }

  static ProviderResponse fail(Outcome outcome) {
    return ProviderResponse.failure(outcome, "model-x", outcome.name());
  }

  static LlmProperties.Provider provider(String key) {
    return new LlmProperties.Provider(LlmProperties.Kind.OPENAI_COMPATIBLE, "http://x", key, 2, null,
        null, null, null);
  }

  static LlmProperties.Model model(String provider, boolean vision, int maxContext, double outPrice) {
    return new LlmProperties.Model(provider, provider + "-model",
        new LlmProperties.Capabilities(LlmProperties.StructuredOutput.JSON, vision,
            LlmProperties.PromptCaching.AUTOMATIC, maxContext),
        Map.of(), new LlmProperties.Pricing(1.0, 0.1, 1.25, outPrice), null);
  }

  /** Every purpose routed to m1 with fallback m2. */
  static LlmProperties props(LlmProperties.Mode mode, Map<String, LlmProperties.Route> overrides) {
    var routes = new HashMap<String, LlmProperties.Route>();
    for (var p : Purpose.values()) routes.put(p.key(), new LlmProperties.Route("m1", List.of("m2")));
    routes.putAll(overrides);
    return new LlmProperties(mode,
        Map.of("a", provider("ka"), "b", provider("kb")),
        Map.of("m1", model("a", true, 100_000, 5.0), "m2", model("b", true, 50_000, 1.0)),
        routes, 24_000, new LlmProperties.Budgets(1, 0.3, 10), false, null);
  }

  final ManualClock clock = new ManualClock(1_790_000_000_000L);
  final Scripted a = new Scripted();
  final Scripted b = new Scripted();
  final LlmCallLog.InMemory calls = new LlmCallLog.InMemory();
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  Budget budget = Budget.UNLIMITED;

  DefaultLlmGateway gateway(LlmProperties props) {
    return new DefaultLlmGateway(RouteTable.build(props), props.maxPromptTokens(),
        Map.of("a", a, "b", b, RouteTable.FAKE_PROVIDER, new FakeLlm()), budget, calls, meters,
        clock, ms -> {}, notes -> "NOTES:" + notes);
  }

  DefaultLlmGateway gateway() {
    return gateway(props(LlmProperties.Mode.LIVE, Map.of()));
  }

  static LlmRequest<Probe> request(Purpose purpose, Priority priority) {
    return new LlmRequest<>(purpose, List.of(new Block("world bible")),
        List.of(Part.text("render this")), Probe.class, 100, Duration.ofSeconds(10), priority);
  }

  static LlmRequest<Probe> request() {
    return request(Purpose.RENDER_THREAD, Priority.SCRIPT);
  }
}
