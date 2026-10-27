package app.radiance.llm;

import static app.radiance.llm.GatewayFixtures.GOOD;
import static app.radiance.llm.GatewayFixtures.fail;
import static app.radiance.llm.GatewayFixtures.ok;
import static app.radiance.llm.GatewayFixtures.props;
import static app.radiance.llm.GatewayFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.radiance.llm.provider.ProviderResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayTests {

  final GatewayFixtures f = new GatewayFixtures();

  @Test
  void fakeModeAnswersEveryPurposeWithNoKeysAndNoCost() {
    var props = new LlmProperties(LlmProperties.Mode.FAKE, null, null, null, 0, null, false, null);
    var gw = f.gateway(props);
    for (var purpose : Purpose.values()) {
      var r = gw.call(request(purpose, Priority.SCRIPT));
      assertThat(r.ok()).isTrue();
      assertThat(r.value()).isNotNull();
      assertThat(r.usage().costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }
    assertThat(gw.call(request()).value()).as("deterministic").isEqualTo(gw.call(request()).value());
    assertThat(f.a.requests).isEmpty();
  }

  @Test
  void movingAPurposeToAnotherProviderIsConfigOnly() {
    var gw = f.gateway(props(LlmProperties.Mode.LIVE,
        Map.of("render-thread", new LlmProperties.Route("m2", List.of()))));
    assertThat(gw.call(request()).ok()).isTrue();
    assertThat(f.a.requests).isEmpty();
    assertThat(f.b.requests).hasSize(1);
    assertThat(f.b.requests.getFirst().model().id()).isEqualTo("b-model");
  }

  @Test
  void invalidOutputGetsOneRepairCallOnTheSameModel() {
    f.a.then(ok("Sure! ```json\n{\"answer\":\"hi\"}\n```")).then(ok("```json\n" + GOOD + "```"));
    var r = f.gateway().call(request());

    assertThat(r.ok()).isTrue();
    assertThat(r.value()).isEqualTo(new GatewayFixtures.Probe("hi", 1));
    assertThat(f.a.requests).hasSize(2);
    var repair = f.a.requests.get(1).content().getLast();
    assertThat(((Part.Text) repair).text()).contains("$.n: missing", "{\"answer\":\"hi\"}");
    assertThat(f.a.requests.get(1).prefix()).as("prefix untouched for caching")
        .isEqualTo(f.a.requests.get(0).prefix());
    assertThat(r.usage().input()).isEqualTo(200);
  }

  @Test
  void aSecondInvalidOutputFallsBackToTheNextModel() {
    f.a.always(ok("not json at all"));
    var r = f.gateway().call(request());
    assertThat(r.ok()).isTrue();
    assertThat(f.a.requests).hasSize(2);
    assertThat(f.b.requests).hasSize(1);
    assertThat(f.calls.entries()).extracting(LlmCallLog.Entry::attempt).containsExactly(1, 2, 3);
  }

  @Test
  void aProviderSchemaErrorCountsAsInvalidOutput() {
    f.a.then(fail(Outcome.INVALID_OUTPUT)).then(ok(GOOD));
    assertThat(f.gateway().call(request()).ok()).isTrue();
    assertThat(f.a.requests).hasSize(2);
  }

  @Test
  void anOutageRetriesThenFallsOverToTheNextModel() {
    f.a.always(fail(Outcome.ERROR));
    var r = f.gateway().call(request());
    assertThat(r.ok()).isTrue();
    assertThat(f.a.requests).hasSize(1 + DefaultLlmGateway.TRANSIENT_RETRIES);
    assertThat(f.b.requests).hasSize(1);
  }

  @Test
  void theCircuitBreakerSkipsAFailingProviderForFiveMinutes() {
    f.a.always(fail(Outcome.TIMEOUT));
    var gw = f.gateway();
    gw.call(request()); // 3 failures
    gw.call(request()); // 2 more: open, so no third retry
    int before = f.a.requests.size();
    assertThat(before).isEqualTo(CircuitBreaker.THRESHOLD);

    gw.call(request());
    assertThat(f.a.requests).hasSize(before);

    f.clock.advance(java.time.Duration.ofMinutes(5).plusSeconds(1));
    f.a.always(ok(GOOD));
    assertThat(gw.call(request()).ok()).isTrue();
    assertThat(f.a.requests).hasSize(before + 1);
  }

  @Test
  void refusalsGoStraightToTheNextModel() {
    f.a.always(fail(Outcome.REFUSED));
    var r = f.gateway().call(request());
    assertThat(r.ok()).isTrue();
    assertThat(f.a.requests).hasSize(1);
  }

  @Test
  void everyModelRefusingReturnsTheRefusal() {
    f.a.always(fail(Outcome.REFUSED));
    f.b.always(fail(Outcome.REFUSED));
    var r = f.gateway().call(request());
    assertThat(r.outcome()).isEqualTo(Outcome.REFUSED);
    assertThat(r.value()).isNull();
  }

  @Test
  void truncationRetriesOnceWithDoubleTheLimit() {
    f.a.then(fail(Outcome.TRUNCATED)).then(ok(GOOD));
    assertThat(f.gateway().call(request()).ok()).isTrue();
    assertThat(f.a.requests).extracting(r -> r.maxOutputTokens()).containsExactly(100, 200);
  }

  @Test
  void cacheReadsAreRecordedAndPricedAtTheCachedRate() {
    f.a.always(ProviderResponse.ok(GOOD, new ProviderResponse.TokenCounts(1000, 4000, 0, 200, 50),
        "a-model-2026"));
    var r = f.gateway().call(request());

    var entry = f.calls.entries().getFirst();
    assertThat(entry.cacheReadTokens()).isEqualTo(4000);
    assertThat(entry.reasoningTokens()).isEqualTo(50);
    assertThat(entry.model()).isEqualTo("a-model-2026");
    assertThat(entry.alias()).isEqualTo("m1");
    // m1: $1 in, $0.10 cached, $5 out per million
    double expected = (1000 * 1.0 + 4000 * 0.1 + 200 * 5.0) / 1e6;
    assertThat(entry.costUsd()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-12));
    assertThat(r.usage().costUsd().doubleValue()).isCloseTo(expected,
        org.assertj.core.data.Offset.offset(1e-12));
    assertThat(f.meters.get("radiance.llm.calls").tag("outcome", "OK").counter().count()).isOne();
    assertThat(f.meters.get("radiance.llm.tokens").tag("kind", "cached").counter().count())
        .isEqualTo(4000);
  }

  @Test
  void aRateLimitClosesTheProviderAndFallsBack() {
    var closed = new ArrayList<String>();
    f.budget = new RecordingBudget(closed);
    f.a.always(new ProviderResponse(Outcome.RATE_LIMITED, null, null, "x", 0, 30_000L, "429"));
    var r = f.gateway().call(request(Purpose.LIVE_COMMENT, Priority.LIVE));
    assertThat(r.ok()).isTrue();
    assertThat(closed).containsExactly("a@" + (f.clock.nowMs() + 30_000));
  }

  @Test
  void nonLiveWorkParksWhenEveryProviderIsRateLimited() {
    f.a.always(new ProviderResponse(Outcome.RATE_LIMITED, null, null, "x", 0, 30_000L, "429"));
    f.b.always(new ProviderResponse(Outcome.RATE_LIMITED, null, null, "x", 0, 90_000L, "429"));
    assertThatThrownBy(() -> f.gateway().call(request()))
        .isInstanceOf(BudgetExhaustedException.class)
        .satisfies(e -> assertThat(((BudgetExhaustedException) e).resumeAt())
            .isEqualTo(f.clock.nowMs() + 90_000));
    var live = f.gateway().call(request(Purpose.LIVE_COMMENT, Priority.LIVE));
    assertThat(live.outcome()).isEqualTo(Outcome.RATE_LIMITED);
  }

  @Test
  void promptNotesAreAppendedToThePrefixForThatModelOnly() {
    var props = props(LlmProperties.Mode.LIVE, Map.of());
    var withNotes = new java.util.HashMap<>(props.models());
    var m1 = props.models().get("m1");
    withNotes.put("m1", new LlmProperties.Model(m1.provider(), m1.id(), m1.capabilities(),
        m1.params(), m1.pricing(), "notes/small.md"));
    var gw = f.gateway(new LlmProperties(props.mode(), props.providers(), withNotes, props.routes(),
        props.maxPromptTokens(), props.budgets(), false, null));
    f.a.always(fail(Outcome.REFUSED));
    gw.call(request());
    assertThat(f.a.requests.getFirst().prefix()).extracting(Block::text)
        .containsExactly("world bible", "NOTES:notes/small.md");
    assertThat(f.b.requests.getFirst().prefix()).extracting(Block::text)
        .containsExactly("world bible");
  }

  @Test
  void budgetForUsesTheSmallestContextOnTheRouteAndTheCap() {
    var gw = f.gateway();
    assertThat(gw.budgetFor(Purpose.RENDER_THREAD).maxPromptTokens()).isEqualTo(24_000);
    var tight = f.gateway(new LlmProperties(LlmProperties.Mode.LIVE,
        props(LlmProperties.Mode.LIVE, Map.of()).providers(),
        Map.of("m1", GatewayFixtures.model("a", true, 100_000, 5), "m2",
            GatewayFixtures.model("b", true, 16_000, 1)),
        props(LlmProperties.Mode.LIVE, Map.of()).routes(), 24_000, null, false, null));
    assertThat(tight.budgetFor(Purpose.RENDER_THREAD).maxPromptTokens()).isEqualTo(16_000);
  }

  @Test
  void callAllWarmsTheCacheWithOneCallThenFansOut() {
    var requests = new ArrayList<LlmRequest<GatewayFixtures.Probe>>();
    for (int i = 0; i < 6; i++) requests.add(request());
    var results = f.gateway().callAll(requests);
    assertThat(results).hasSize(6).allMatch(LlmResult::ok);
    assertThat(f.a.requests).hasSize(6);
  }

  @Test
  void atTheLastRungLiveCallsUseTheCheapestModelFirst() {
    f.budget = new RecordingBudget(new ArrayList<>()) {
      @Override
      public double pressure() {
        return 1.0;
      }
    };
    var gw = f.gateway();
    assertThat(gw.degradationLevel()).isEqualTo(6);
    gw.call(request(Purpose.LIVE_COMMENT, Priority.LIVE));
    assertThat(f.b.requests).hasSize(1);
    assertThat(f.a.requests).isEmpty();
    gw.call(request());
    assertThat(f.a.requests).as("non-live keeps route order").hasSize(1);
  }

  static class RecordingBudget implements Budget {
    final List<String> closed;

    RecordingBudget(List<String> closed) {
      this.closed = closed;
    }

    @Override
    public void admit(Priority p, String provider, double usd, long tokens) {}

    @Override
    public void record(Priority p, String provider, double usd, long tokens) {}

    @Override
    public void closeProvider(String provider, long untilMs) {
      closed.add(provider + "@" + untilMs);
    }

    @Override
    public double pressure() {
      return 0;
    }
  }
}
