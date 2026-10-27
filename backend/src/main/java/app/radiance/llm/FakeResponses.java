package app.radiance.llm;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Believable FakeLlm output for a record type (SPEC §5.10): domain modules register a template so the
 * offline profile produces usable content (genesis prose now, render templates in M5). Types without a
 * template get schema-filled values.
 */
public final class FakeResponses {

  /** What a template sees of the request: plain text only. */
  public record FakeCall(Purpose purpose, List<String> prefix, List<String> content) {
    public String allContent() {
      return String.join("\n", content);
    }
  }

  private final Map<Class<?>, Function<FakeCall, ?>> templates = new ConcurrentHashMap<>();

  public <T> void register(Class<T> type, Function<FakeCall, T> template) {
    templates.put(type, template);
  }

  public Optional<Function<FakeCall, ?>> forType(Class<?> type) {
    return Optional.ofNullable(templates.get(type));
  }
}
