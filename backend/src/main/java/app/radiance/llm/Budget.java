package app.radiance.llm;

/**
 * Spending and quota windows that reset (SPEC §5.15). The gateway asks before every attempt and
 * reports what each attempt cost.
 */
public interface Budget {

  /**
   * Throws if a call of this estimated size doesn't fit. USD windows ({@code usd:*}) apply by priority:
   * live calls see only the monthly cap. Provider windows ({@code <provider>:*}) apply to everyone.
   */
  void admit(Priority priority, String provider, double estimateUsd, long estimateTokens);

  void record(Priority priority, String provider, double costUsd, long tokens);

  /** A provider's 429 or quota error: no calls to it until {@code untilMs} (wall time). */
  void closeProvider(String provider, long untilMs);

  /** The highest used/limit fraction across the USD windows, for the degradation ladder. */
  double pressure();

  /** No limits at all; for tests that aren't about budgets. */
  Budget UNLIMITED =
      new Budget() {
        @Override
        public void admit(Priority p, String provider, double usd, long tokens) {}

        @Override
        public void record(Priority p, String provider, double usd, long tokens) {}

        @Override
        public void closeProvider(String provider, long untilMs) {}

        @Override
        public double pressure() {
          return 0;
        }
      };
}
