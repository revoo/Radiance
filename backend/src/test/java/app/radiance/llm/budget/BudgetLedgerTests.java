package app.radiance.llm.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.radiance.kernel.ManualClock;
import app.radiance.llm.BudgetExhaustedException;
import app.radiance.llm.LlmProperties;
import app.radiance.llm.Priority;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BudgetLedgerTests {

  static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
  // Tuesday 2026-10-27, 3:00 pm Central
  static final long T = ZonedDateTime.of(2026, 10, 27, 15, 0, 0, 0, CHICAGO).toInstant().toEpochMilli();

  final ManualClock clock = new ManualClock(T);
  final BudgetStore.InMemory store = new BudgetStore.InMemory();

  static LlmProperties.Provider provider(LlmProperties.Quota... quotas) {
    return new LlmProperties.Provider(LlmProperties.Kind.OPENAI_COMPATIBLE, "http://x", "k", 2,
        null, null, List.of(quotas), null);
  }

  BudgetLedger ledger(double daily, double monthly) {
    return new BudgetLedger(new LlmProperties.Budgets(daily, 0.30, monthly),
        Map.of("a", provider(), "q", provider(
            new LlmProperties.Quota(3L, null, LlmProperties.Quota.Per.MINUTE, null, null),
            new LlmProperties.Quota(null, 1000L, LlmProperties.Quota.Per.DAY, "00:00", "America/Los_Angeles"))),
        CHICAGO, clock, store);
  }

  static long at(int month, int day, int hour) {
    return ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, CHICAGO).toInstant().toEpochMilli();
  }

  @Test
  void aDailyBreachParksWorkUntilMidnightCentralThenResumes() {
    var ledger = ledger(1.00, 10.00);
    ledger.admit(Priority.SCRIPT, "a", 0.50, 1);
    ledger.record(Priority.SCRIPT, "a", 0.90, 1);

    assertThatThrownBy(() -> ledger.admit(Priority.SCRIPT, "a", 0.20, 1))
        .isInstanceOf(BudgetExhaustedException.class)
        .satisfies(e -> {
          var b = (BudgetExhaustedException) e;
          assertThat(b.windowId()).isEqualTo(BudgetLedger.DAILY);
          assertThat(b.resumeAt()).isEqualTo(at(10, 28, 0));
        });

    clock.set(at(10, 28, 0) - 1);
    assertThatThrownBy(() -> ledger.admit(Priority.BACKGROUND, "a", 0.20, 1))
        .isInstanceOf(BudgetExhaustedException.class);
    clock.set(at(10, 28, 0));
    ledger.admit(Priority.SCRIPT, "a", 0.20, 1); // the window reset
  }

  @Test
  void liveCallsIgnoreTheDailyBudgetButStopAtTheMonthlyCap() {
    var ledger = ledger(1.00, 2.00);
    ledger.record(Priority.SCRIPT, "a", 1.00, 1);
    ledger.admit(Priority.LIVE, "a", 0.50, 1);
    ledger.record(Priority.LIVE, "a", 0.95, 1);
    assertThatThrownBy(() -> ledger.admit(Priority.LIVE, "a", 0.10, 1))
        .satisfies(e -> {
          var b = (BudgetExhaustedException) e;
          assertThat(b.windowId()).isEqualTo(BudgetLedger.MONTHLY);
          assertThat(b.resumeAt()).isEqualTo(at(11, 1, 0));
        });
  }

  @Test
  void providerQuotasCountRequestsAndTokensAndResetOnTheirOwnSchedule() {
    var ledger = ledger(100, 100);
    for (int i = 0; i < 3; i++) {
      ledger.admit(Priority.SCRIPT, "q", 0, 10);
      ledger.record(Priority.SCRIPT, "q", 0, 10);
    }
    assertThatThrownBy(() -> ledger.admit(Priority.LIVE, "q", 0, 10))
        .satisfies(e -> assertThat(((BudgetExhaustedException) e).windowId()).isEqualTo("q:requests:minute"));
    ledger.admit(Priority.SCRIPT, "a", 0, 10); // other providers are unaffected

    clock.advance(Duration.ofMinutes(1));
    ledger.admit(Priority.SCRIPT, "q", 0, 10);
    assertThatThrownBy(() -> ledger.admit(Priority.SCRIPT, "q", 0, 980))
        .satisfies(e -> {
          var b = (BudgetExhaustedException) e;
          assertThat(b.windowId()).isEqualTo("q:tokens:day");
          // midnight Pacific is 2 am Central
          assertThat(b.resumeAt()).isEqualTo(at(10, 28, 2));
        });
  }

  @Test
  void aQuotaErrorClosesTheProviderUntilTheReportedReset() {
    var ledger = ledger(100, 100);
    ledger.closeProvider("a", T + 90_000);
    assertThatThrownBy(() -> ledger.admit(Priority.LIVE, "a", 0, 1))
        .satisfies(e -> {
          var b = (BudgetExhaustedException) e;
          assertThat(b.windowId()).isEqualTo("a:closed");
          assertThat(b.resumeAt()).isEqualTo(T + 90_000);
        });
    ledger.admit(Priority.LIVE, "q", 0, 1);
    clock.advance(Duration.ofSeconds(90));
    ledger.admit(Priority.LIVE, "a", 0, 1);
  }

  @Test
  void aBatchHasItsOwnCapThatLiveSpendingDoesNotTouch() {
    var ledger = ledger(100, 100);
    ledger.openBatch(0.30);
    ledger.record(Priority.LIVE, "a", 0.25, 1);
    ledger.record(Priority.SCRIPT, "a", 0.25, 1);
    assertThatThrownBy(() -> ledger.admit(Priority.SCRIPT, "a", 0.10, 1))
        .satisfies(e -> assertThat(((BudgetExhaustedException) e).windowId()).isEqualTo(BudgetLedger.BATCH));
    ledger.admit(Priority.LIVE, "a", 0.10, 1);
    ledger.closeBatch();
    ledger.admit(Priority.SCRIPT, "a", 0.10, 1);
  }

  @Test
  void pressureIsTheFullestUsdWindow() {
    var ledger = ledger(1.00, 10.00);
    assertThat(ledger.pressure()).isZero();
    ledger.record(Priority.SCRIPT, "a", 0.85, 1);
    assertThat(ledger.pressure()).isCloseTo(0.85, org.assertj.core.data.Offset.offset(1e-9));
    clock.set(at(10, 28, 0));
    assertThat(ledger.pressure()).isCloseTo(0.085, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void stateSurvivesARestartAndOnlyChangedWindowsAreWritten() {
    var first = ledger(1.00, 10.00);
    first.record(Priority.SCRIPT, "a", 0.95, 1);
    first.closeProvider("a", T + 60_000);
    assertThat(store.load()).extracting(BudgetStore.Row::id)
        .containsExactlyInAnyOrder(BudgetLedger.DAILY, BudgetLedger.MONTHLY, "a:closed");

    var second = ledger(1.00, 10.00);
    assertThatThrownBy(() -> second.admit(Priority.SCRIPT, "q", 0.10, 1))
        .satisfies(e -> assertThat(((BudgetExhaustedException) e).windowId()).isEqualTo(BudgetLedger.DAILY));
    assertThatThrownBy(() -> second.admit(Priority.LIVE, "a", 0, 1))
        .satisfies(e -> assertThat(((BudgetExhaustedException) e).windowId()).isEqualTo("a:closed"));
  }
}
