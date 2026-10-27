package app.radiance.llm.provider;

import static app.radiance.llm.provider.AdapterFixtures.model;
import static app.radiance.llm.provider.AdapterFixtures.provider;
import static app.radiance.llm.provider.AdapterFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.llm.LlmProperties.PromptCaching;
import app.radiance.llm.LlmProperties.StructuredOutput;
import app.radiance.llm.Outcome;
import app.radiance.llm.Part;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingsTests {

  @TempDir Path dir;

  @Test
  void replayServesExactlyWhatWasRecorded() {
    var m = model("m", StructuredOutput.SCHEMA, PromptCaching.NONE, Map.of());
    var p = provider("http://x", null);
    var live = (LlmProvider) r -> ProviderResponse.ok("{\"answer\":\"real\",\"n\":7}",
        new ProviderResponse.TokenCounts(10, 20, 0, 5, 1), "m-2026");
    var recorded = Recordings.recording(live, dir).complete(request(m, p));

    var replayed = Recordings.replay(dir).complete(request(m, p));
    assertThat(replayed).isEqualTo(recorded);

    var other = Recordings.replay(dir).complete(request(m, p, List.of(Part.text("different")),
        Duration.ofSeconds(1)));
    assertThat(other.outcome()).isEqualTo(Outcome.ERROR);
    assertThat(other.error()).startsWith("no recording");
  }

  @Test
  void theKeyIgnoresAliasAndTimeoutButNotTheModel() {
    var m = model("m", StructuredOutput.SCHEMA, PromptCaching.NONE, Map.of());
    var p = provider("http://x", null);
    var a = request(m, p);
    var b = new ProviderRequest(a.purpose(), "renamed", a.model(), a.provider(), a.prefix(),
        a.content(), a.outputType(), a.schema(), a.schemaName(), a.maxOutputTokens(),
        Duration.ofMinutes(9));
    assertThat(Recordings.key(b)).isEqualTo(Recordings.key(a));
    var otherModel = request(model("m2", StructuredOutput.SCHEMA, PromptCaching.NONE, Map.of()), p);
    assertThat(Recordings.key(otherModel)).isNotEqualTo(Recordings.key(a));
  }
}
