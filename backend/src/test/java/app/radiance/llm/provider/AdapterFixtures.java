package app.radiance.llm.provider;

import app.radiance.llm.Block;
import app.radiance.llm.LlmProperties;
import app.radiance.llm.Part;
import app.radiance.llm.Purpose;
import app.radiance.llm.output.OutputSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class AdapterFixtures {

  record Probe(String answer, int n) {}

  static LlmProperties.Model model(String id, LlmProperties.StructuredOutput so,
      LlmProperties.PromptCaching caching, Map<String, Object> params) {
    return new LlmProperties.Model("p", id,
        new LlmProperties.Capabilities(so, true, caching, 100_000), params,
        new LlmProperties.Pricing(0.1, 0.01, 0.125, 0.5), null);
  }

  static LlmProperties.Provider provider(String baseUrl, String maxTokensField) {
    return new LlmProperties.Provider(LlmProperties.Kind.OPENAI_COMPATIBLE, baseUrl, "sk-test", 2,
        maxTokensField, Map.of("x-extra", "1"), null, null);
  }

  static ProviderRequest request(LlmProperties.Model model, LlmProperties.Provider provider,
      List<Part> content, Duration timeout) {
    return new ProviderRequest(Purpose.RENDER_THREAD, "alias", model, provider,
        List.of(new Block("WORLD BIBLE"), new Block("CAST DIRECTORY")), content, Probe.class,
        OutputSchema.of(Probe.class), OutputSchema.name(Probe.class), 300, timeout);
  }

  static ProviderRequest request(LlmProperties.Model model, LlmProperties.Provider provider) {
    return request(model, provider, List.of(Part.text("TODAY"), Part.text("BRIEFS")),
        Duration.ofSeconds(5));
  }
}
