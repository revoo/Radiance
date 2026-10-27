package app.radiance.llm;

import app.radiance.kernel.WallClock;
import app.radiance.llm.budget.BudgetLedger;
import app.radiance.llm.budget.SqlBudgetStore;
import app.radiance.llm.provider.AnthropicLlm;
import app.radiance.llm.provider.FakeLlm;
import app.radiance.llm.provider.LlmProvider;
import app.radiance.llm.provider.OpenAiCompatibleLlm;
import app.radiance.llm.provider.Recordings;
import app.radiance.persistence.Sql;
import app.radiance.persistence.WriterLane;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class LlmConfig {

  /**
   * {@code radiance.llm.recordings}: {@code RECORD} saves every real answer under {@code dir};
   * {@code REPLAY} serves only saved answers (SPEC §5.10).
   */
  @ConfigurationProperties("radiance.llm.recordings")
  record RecordingProperties(Mode mode, String dir) {
    enum Mode { OFF, RECORD, REPLAY }

    RecordingProperties {
      mode = mode == null ? Mode.OFF : mode;
      dir = dir == null ? "data/llm-recordings" : dir;
    }
  }

  @Bean
  RouteTable routeTable(LlmProperties props) {
    return RouteTable.build(props);
  }

  /** The adapters by provider name. A record, not a bare Map, so Spring injects it as one bean. */
  record Providers(Map<String, LlmProvider> byName) {}

  /** One adapter per configured provider, plus FakeLlm. */
  @Bean
  Providers llmProviders(LlmProperties props, RecordingProperties recordings) {
    var out = new HashMap<String, LlmProvider>();
    props.providers().forEach((name, p) -> {
      var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
      var builder = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(http));
      LlmProvider adapter = switch (p.kind()) {
        case ANTHROPIC -> new AnthropicLlm(p, builder);
        case OPENAI_COMPATIBLE -> new OpenAiCompatibleLlm(p, builder);
      };
      out.put(name, switch (recordings.mode()) {
        case OFF -> adapter;
        case RECORD -> Recordings.recording(adapter, Path.of(recordings.dir()));
        case REPLAY -> Recordings.replay(Path.of(recordings.dir()));
      });
    });
    out.put(RouteTable.FAKE_PROVIDER, new FakeLlm());
    return new Providers(out);
  }

  @Bean
  BudgetLedger budgetLedger(LlmProperties props, WallClock clock, Sql sql, WriterLane lane,
      @Value("${radiance.world.timezone:America/Chicago}") String timezone) {
    return new BudgetLedger(props.budgets(), props.providers(), ZoneId.of(timezone), clock,
        new SqlBudgetStore(sql, lane));
  }

  @Bean
  LlmCallLog llmCallLog(Sql sql, WriterLane lane) {
    return new SqlLlmCallLog(sql, lane);
  }

  @Bean
  DefaultLlmGateway llmGateway(LlmProperties props, RouteTable routes,
      Providers llmProviders, BudgetLedger budget, LlmCallLog callLog,
      MeterRegistry meters, WallClock clock, ResourceLoader resources) {
    var notes = promptNotes(props, resources);
    var gateway = new DefaultLlmGateway(routes, props.maxPromptTokens(), llmProviders.byName(), budget,
        callLog, meters, clock, Thread::sleep, notes::get);
    if (props.storePrompts()) gateway.promptStore(new PromptStore.Files(Path.of(props.promptStoreDir())));
    return gateway;
  }

  /** Loads every alias's prompt-notes file now, so a missing one fails startup, not a render. */
  private static Map<String, String> promptNotes(LlmProperties props, ResourceLoader resources) {
    var notes = new ConcurrentHashMap<String, String>();
    for (var model : props.models().values()) {
      String path = model.promptNotes();
      if (path == null || notes.containsKey(path)) continue;
      var resource = resources.getResource("classpath:" + path);
      if (!resource.exists()) {
        throw new IllegalStateException("prompt-notes file not found on the classpath: " + path);
      }
      try {
        notes.put(path, resource.getContentAsString(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return notes;
  }
}
