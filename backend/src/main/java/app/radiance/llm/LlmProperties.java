package app.radiance.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code radiance.llm} (SPEC §9): providers (one per endpoint), model aliases, purpose routes,
 * budgets. Provider- and model-specific detail lives here and in the adapters, never in domain code.
 */
@ConfigurationProperties("radiance.llm")
public record LlmProperties(
    Mode mode,
    Map<String, Provider> providers,
    Map<String, Model> models,
    Map<String, Route> routes,
    int maxPromptTokens,
    Budgets budgets,
    boolean storePrompts,
    String promptStoreDir) {

  public LlmProperties {
    mode = mode == null ? Mode.LIVE : mode;
    providers = providers == null ? Map.of() : providers;
    models = models == null ? Map.of() : models;
    routes = routes == null ? Map.of() : routes;
    maxPromptTokens = maxPromptTokens <= 0 ? 24_000 : maxPromptTokens;
    budgets = budgets == null ? new Budgets(1.00, 0.30, 10.00) : budgets;
    promptStoreDir = promptStoreDir == null ? "data/prompts" : promptStoreDir;
  }

  /** {@code FAKE} serves every route with FakeLlm (offline, tests); {@code LIVE} uses real providers. */
  public enum Mode {
    FAKE,
    LIVE
  }

  public enum Kind {
    ANTHROPIC,
    OPENAI_COMPATIBLE
  }

  /**
   * @param apiKey null means the endpoint needs no key (a local runner); blank means a key is required
   *     but missing, so its models are skipped
   * @param maxTokensField the request field for the output limit on OpenAI-compatible endpoints
   *     ({@code max_tokens} by default; OpenAI itself wants {@code max_completion_tokens})
   */
  public record Provider(
      Kind kind,
      String baseUrl,
      String apiKey,
      int maxConcurrency,
      String maxTokensField,
      Map<String, String> headers,
      List<Quota> quotas,
      Duration timeout) {

    public Provider {
      maxConcurrency = maxConcurrency <= 0 ? 4 : maxConcurrency;
      headers = headers == null ? Map.of() : headers;
      quotas = quotas == null ? List.of() : quotas;
      timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
    }

    public boolean hasKey() {
      return apiKey == null || !apiKey.isBlank();
    }
  }

  /** A plan limit: {@code requests} or {@code tokens} per minute or day, resetting at a local time. */
  public record Quota(Long requests, Long tokens, Per per, String resetsAt, String zone) {
    public enum Per {
      MINUTE,
      DAY
    }
  }

  public record Model(
      String provider,
      String id,
      Capabilities capabilities,
      Map<String, Object> params,
      Pricing pricing,
      String promptNotes) {

    public Model {
      capabilities = capabilities == null ? Capabilities.NONE : capabilities;
      params = params == null ? Map.of() : params;
      pricing = pricing == null ? new Pricing(0, 0, 0, 0) : pricing;
    }
  }

  public record Capabilities(
      StructuredOutput structuredOutput, boolean vision, PromptCaching promptCaching, int maxContext) {

    static final Capabilities NONE = new Capabilities(null, false, null, 0);

    public Capabilities {
      structuredOutput = structuredOutput == null ? StructuredOutput.NONE : structuredOutput;
      promptCaching = promptCaching == null ? PromptCaching.NONE : promptCaching;
      maxContext = maxContext <= 0 ? 8_000 : maxContext;
    }
  }

  public enum StructuredOutput {
    SCHEMA,
    JSON,
    NONE
  }

  public enum PromptCaching {
    EXPLICIT,
    AUTOMATIC,
    NONE
  }

  /** USD per million tokens. */
  public record Pricing(double input, double cachedInput, double cacheWrite, double output) {}

  public record Route(String model, List<String> fallback) {
    public Route {
      fallback = fallback == null ? List.of() : fallback;
    }
  }

  public record Budgets(double dailyUsd, double perBatchUsd, double monthlyUsd) {}
}
