package app.radiance.llm;

import app.radiance.kernel.WallClock;
import app.radiance.llm.provider.FakeLlm;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;

/**
 * A gateway that answers every purpose from {@link FakeResponses}, for offline tools such as the
 * {@code simulate} harness: no keys, no network, no budget.
 */
public final class OfflineGateway {

  private OfflineGateway() {}

  public static LlmGateway create(FakeResponses fakes, WallClock clock) {
    var props = new LlmProperties(LlmProperties.Mode.FAKE, null, null, null, 0, null, false, null);
    return new DefaultLlmGateway(RouteTable.build(props), 24_000, Map.of(RouteTable.FAKE_PROVIDER, new FakeLlm(fakes)),
        Budget.UNLIMITED, new LlmCallLog.InMemory(), new SimpleMeterRegistry(), clock, ms -> { }, p -> "");
  }
}
