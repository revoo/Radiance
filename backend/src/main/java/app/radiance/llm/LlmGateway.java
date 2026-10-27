package app.radiance.llm;

import java.util.List;

/** The only way any code reaches a model (SPEC §5.10). */
public interface LlmGateway {

  /**
   * Routed, budget-checked, validated, metered and retried.
   *
   * @throws BudgetExhaustedException when the call doesn't fit a budget or quota window; non-live work
   *     parks until {@link BudgetExhaustedException#resumeAt()}
   */
  <T> LlmResult<T> call(LlmRequest<T> request);

  /**
   * A batch of similar calls: the first goes alone so the provider's prefix cache is warm, the rest
   * fan out on virtual threads. Results come back in request order.
   */
  <T> List<LlmResult<T>> callAll(List<LlmRequest<T>> requests);

  /** Token budget and estimator for the packet builder. */
  PromptBudget budgetFor(Purpose purpose);

  /** The current rung of the degradation ladder, 0 (none) to 6 (SPEC §5.15). */
  int degradationLevel();
}
