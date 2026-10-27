package app.radiance.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import app.radiance.kernel.WallClock;
import app.radiance.llm.output.OutputSchema;
import app.radiance.llm.provider.LlmProvider;
import app.radiance.llm.provider.ProviderRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Live contract tests (SPEC §10), run by hand: {@code ./gradlew :backend:liveTest}. For every model
 * alias that a route uses and whose provider has a key, one call per purpose it serves, plus checks of
 * what it declares: structured output, vision, prompt caching, and reasoning kept where intended.
 * Tiny prompts; a full run costs well under a cent on the default models. Never runs in CI.
 */
@Tag("live")
@SpringBootTest
@ActiveProfiles({"offline", "test", "livetest"}) // livetest last: it switches the mode back to live
class LiveContractTests {

  @Autowired LlmProperties props;
  @Autowired LlmConfig.Providers providers;
  @Autowired WallClock clock;

  enum Mood { HAPPY, SAD, NEUTRAL }

  enum Colour { RED, GREEN, BLUE, OTHER }

  record Line(String speaker, String text, Mood mood) {}

  record Probe(String greeting, int count, boolean ok, List<Line> lines) {}

  record ImageProbe(Colour dominantColour, String description) {}

  record Echo(String word) {}

  /** Aliases referenced by any route whose provider has a key, with the purposes they serve. */
  private Map<String, List<Purpose>> aliasesInUse() {
    var out = new HashMap<String, List<Purpose>>();
    props.routes().forEach((key, route) -> {
      var aliases = new ArrayList<String>();
      aliases.add(route.model());
      aliases.addAll(route.fallback());
      for (String alias : aliases) {
        var model = props.models().get(alias);
        if (props.providers().get(model.provider()).hasKey()) {
          out.computeIfAbsent(alias, a -> new ArrayList<>()).add(Purpose.fromKey(key));
        }
      }
    });
    return out;
  }

  /** A gateway whose every route is {@code alias} alone, so failures can't hide behind fallbacks. */
  private DefaultLlmGateway gatewayFor(String alias, LlmCallLog log) {
    var routes = new HashMap<String, LlmProperties.Route>();
    for (var p : Purpose.values()) routes.put(p.key(), new LlmProperties.Route(alias, List.of()));
    var only = new LlmProperties(LlmProperties.Mode.LIVE, props.providers(), props.models(), routes,
        props.maxPromptTokens(), props.budgets(), false, null);
    return new DefaultLlmGateway(RouteTable.build(only), props.maxPromptTokens(),
        providers.byName(), Budget.UNLIMITED, log, new SimpleMeterRegistry(), clock, Thread::sleep,
        path -> "");
  }

  @TestFactory
  Stream<DynamicTest> everyAliasInUseHonoursItsContract() {
    var aliases = aliasesInUse();
    if (aliases.isEmpty()) {
      return Stream.of(dynamicTest("no provider keys configured: nothing to test", () -> { }));
    }
    var tests = new ArrayList<DynamicTest>();
    for (String alias : new TreeSet<>(aliases.keySet())) {
      var model = props.models().get(alias);
      var log = new LlmCallLog.InMemory();
      var gw = gatewayFor(alias, log);

      for (Purpose purpose : aliases.get(alias)) {
        tests.add(dynamicTest(alias + " serves " + purpose.key(), () -> {
          var r = gw.call(new LlmRequest<>(purpose,
              List.of(new Block("You are testing an API. Follow the output format exactly.")),
              List.of(Part.text("Greet the reader, count to 3, set ok to true, and write two short"
                  + " lines of dialogue between Ann and Bob with their moods.")),
              Probe.class, 400, Duration.ofSeconds(60), Priority.BACKGROUND));
          assertThat(r.outcome()).as(alias + " on " + purpose.key()).isEqualTo(Outcome.OK);
          assertThat(r.value().lines()).isNotEmpty();
        }));
      }

      if (model.capabilities().vision()) {
        tests.add(dynamicTest(alias + " sees images", () -> {
          var r = gw.call(new LlmRequest<>(Purpose.VISION_APPRAISAL, List.of(new Block("Describe images neutrally.")),
              List.of(Part.text("What colour fills this image?"), new Part.Image("image/png", redSquare())),
              ImageProbe.class, 200, Duration.ofSeconds(60), Priority.BACKGROUND));
          assertThat(r.outcome()).isEqualTo(Outcome.OK);
          assertThat(r.value().dominantColour()).isEqualTo(Colour.RED);
        }));
      }

      if (model.capabilities().promptCaching() != LlmProperties.PromptCaching.NONE) {
        tests.add(dynamicTest(alias + " reports prompt-cache reads", () -> {
          var prefix = List.of(new Block(longPrefix()));
          LlmResult<Echo> second = null;
          for (int i = 0; i < 2; i++) {
            second = gw.call(new LlmRequest<>(Purpose.SUMMARY, prefix,
                List.of(Part.text("Return the word 'cached'.")), Echo.class, 50,
                Duration.ofSeconds(60), Priority.BACKGROUND));
            assertThat(second.ok()).isTrue();
          }
          assertThat(second.usage().cachedInput()).as("second call reads the cached prefix").isPositive();
        }));
      }

      tests.add(dynamicTest(alias + " keeps reasoning where its params put it", () -> {
        var request = new ProviderRequest(Purpose.LINTER_JUDGE, alias, model,
            props.providers().get(model.provider()), List.of(new Block("Answer briefly.")),
            List.of(Part.text("Return the word 'yes'.")), Echo.class, OutputSchema.of(Echo.class),
            OutputSchema.name(Echo.class), 100, Duration.ofSeconds(60));
        LlmProvider adapter = providers.byName().get(model.provider());
        var resp = adapter.complete(request);
        assertThat(resp.outcome()).isEqualTo(Outcome.OK);
        Object thinking = model.params().get("thinking");
        Object effort = model.params().get("reasoning_effort");
        if ("disabled".equals(String.valueOf(thinking))) {
          assertThat(resp.thinkingBlocks()).as("thinking disabled ⇒ no thinking blocks").isZero();
        }
        if ("none".equals(String.valueOf(effort))) {
          assertThat(resp.tokens().reasoning()).as("reasoning_effort none ⇒ no reasoning tokens").isZero();
        }
      }));
    }
    return tests.stream();
  }

  private static String redSquare() throws IOException {
    var img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
    var g = img.createGraphics();
    g.setColor(Color.RED);
    g.fillRect(0, 0, 64, 64);
    g.dispose();
    var out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return Base64.getEncoder().encodeToString(out.toByteArray());
  }

  /** Comfortably past every provider's minimum cacheable prefix (512–1,024 tokens). */
  private static String longPrefix() {
    var sb = new StringBuilder("Reference notes for a cache test. ");
    for (int i = 0; i < 400; i++) {
      sb.append("Note ").append(i).append(": the lake is cold in April and warm in August. ");
    }
    return sb.toString();
  }
}
