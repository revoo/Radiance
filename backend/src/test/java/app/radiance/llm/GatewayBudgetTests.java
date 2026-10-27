package app.radiance.llm;

import static app.radiance.llm.GatewayFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.radiance.llm.budget.BudgetLedger;
import app.radiance.llm.budget.BudgetStore;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M2 AC: a budget breach parks work until its window resets (fake clock). */
class GatewayBudgetTests {

  final GatewayFixtures f = new GatewayFixtures();

  @Test
  void aBreachRefusesWorkBeforeAnythingIsSentAndResumesAfterTheReset() {
    var props = GatewayFixtures.props(LlmProperties.Mode.LIVE, Map.of());
    var ledger = new BudgetLedger(new LlmProperties.Budgets(0.0010, 0.30, 10), props.providers(),
        ZoneId.of("America/Chicago"), f.clock, new BudgetStore.InMemory());
    f.budget = ledger;
    var gw = f.gateway(props);

    // m1: $1 in, $5 out per million. Each call costs 100 in + 20 out = $0.0002. Admission estimates
    // the whole prompt uncached plus the full 100-token output limit: about $0.000508.
    for (int i = 0; i < 3; i++) assertThat(gw.call(request()).ok()).isTrue();
    assertThat(f.a.requests).hasSize(3);

    // $0.0006 spent + $0.000508 estimated > $0.001
    var parked = org.assertj.core.api.Assertions.catchThrowableOfType(
        BudgetExhaustedException.class, () -> gw.call(request()));
    assertThat(parked.windowId()).isEqualTo(BudgetLedger.DAILY);
    assertThat(f.a.requests).as("nothing was sent").hasSize(3);

    f.clock.set(parked.resumeAt() - 1);
    assertThatThrownBy(() -> gw.call(request())).isInstanceOf(BudgetExhaustedException.class);
    f.clock.set(parked.resumeAt());
    assertThat(gw.call(request()).ok()).as("resumes after the reset").isTrue();
  }

  @Test
  void liveCallsKeepGoingPastTheDailyBudget() {
    var props = GatewayFixtures.props(LlmProperties.Mode.LIVE, Map.of());
    var ledger = new BudgetLedger(new LlmProperties.Budgets(0.0001, 0.30, 10), props.providers(),
        ZoneId.of("America/Chicago"), f.clock, new BudgetStore.InMemory());
    f.budget = ledger;
    var gw = f.gateway(props);
    assertThatThrownBy(() -> gw.call(request())).isInstanceOf(BudgetExhaustedException.class);
    assertThat(gw.call(request(Purpose.LIVE_COMMENT, Priority.LIVE)).ok()).isTrue();
    f.clock.advance(Duration.ofMinutes(1));
    assertThat(gw.degradationLevel()).isEqualTo(6);
  }
}
