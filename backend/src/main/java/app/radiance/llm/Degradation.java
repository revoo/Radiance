package app.radiance.llm;

/**
 * The degradation ladder (SPEC §5.15), driven by budget pressure: the highest used/limit fraction
 * across the USD windows. Below 80% nothing degrades; from 80% to 100% the six rungs engage in equal
 * steps (ADR 0014). Each rung's consumer reads {@link LlmGateway#degradationLevel()}:
 *
 * <ol>
 *   <li>pause compaction and summary refreshes (M5)
 *   <li>write the next script at REDUCED fidelity (M6)
 *   <li>at most 2 rendered comments per scripted thread (M5)
 *   <li>script posts only; comments become reactions (M5)
 *   <li>stop rendering scripts; they air as reactions and life events (M5)
 *   <li>live routes move to their cheapest model (the gateway, here)
 * </ol>
 */
public final class Degradation {

  public static final double START = 0.8;
  public static final int RUNGS = 6;
  public static final int CHEAPEST_LIVE = 6;

  private Degradation() {}

  public static int level(double pressure) {
    if (pressure < START) return 0;
    int level = 1 + (int) Math.floor((pressure - START) / ((1.0 - START) / RUNGS));
    return Math.min(RUNGS, level);
  }
}
