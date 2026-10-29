package app.radiance.narrative;

import app.radiance.content.ContentLibrary;
import app.radiance.kernel.Json;
import app.radiance.kernel.RandomSource;
import app.radiance.llm.Block;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.LlmRequest;
import app.radiance.llm.LlmResult;
import app.radiance.llm.Outcome;
import app.radiance.llm.Part;
import app.radiance.llm.PromptBudget;
import app.radiance.llm.Priority;
import app.radiance.llm.Purpose;
import app.radiance.sim.Simulation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The bake-off (SPEC §5.10): the same fixture packets through each candidate model, scored on schema
 * validity, repairs, linter and blacklist hits, LLM-isms, length bands, refusals, latency, cache hits
 * and cost per 1,000 items; plus a blind side-by-side file (outputs labeled A, B, C; the key kept apart)
 * for R to judge voice by eye. Outputs are written down, so re-scoring is free.
 */
public final class Bakeoff {

  /** One packet to render, and what each brief asked for. */
  public record Fixture(String id, String label, List<Block> prefix, String content, Map<String, Expect> briefs) {}

  public record Expect(String author, String kind, String stance, String lengthBand) {}

  /** One model's scores. */
  public record Report(String alias, int fixtures, int items, double schemaValid, double repaired, int refusals,
      int truncations, int linterHits, int blacklistHits, int llmIsms, double lengthCompliance, long p50Ms, long p95Ms,
      double cacheHitRate, double costPer1000Items) {}

  /** What came back for one fixture from one model. */
  public record Output(String fixture, String alias, String outcome, long latencyMs, Map<String, String> texts) {}

  private final ContentLibrary lib;

  public Bakeoff(ContentLibrary lib) {
    this.lib = lib;
  }

  // ------------------------------------------------------------------------------------- fixtures

  /**
   * About {@code n} RENDER_THREAD packets from a simulated world, chosen for spread: a politically
   * charged thread, an ugly one (mockery, a prejudiced remark, the backlash), a secret-keeping one, then
   * the rest across categories. The same world gives the same fixtures.
   */
  public static List<Fixture> fixtures(Simulation sim, int n) {
    var builder = new ContextPacketBuilder(sim.sql, sim.lib, "Radiance", sim.engine.world());
    var budget = new PromptBudget(24_000, 4.0);
    var picked = new LinkedHashMap<String, String>();
    pick(sim, picked, "political", "SELECT p.id FROM post p WHERE p.kind = 'LINK' AND p.body IS NULL AND EXISTS"
        + " (SELECT 1 FROM comment c WHERE c.post_id = p.id AND c.brief_json LIKE '%DISAGREE%') ORDER BY p.created_at LIMIT 3");
    pick(sim, picked, "ugly", "SELECT p.id FROM post p WHERE p.body IS NULL AND EXISTS (SELECT 1 FROM comment c WHERE"
        + " c.post_id = p.id AND (c.brief_json LIKE '%\"MOCK\"%' OR c.brief_json LIKE '%\"HOSTILE\"%' OR c.brief_json LIKE"
        + " '%\"TROLL\"%')) ORDER BY p.created_at LIMIT 3");
    pick(sim, picked, "arc beat", "SELECT id FROM post WHERE body IS NULL AND arc_id IS NOT NULL ORDER BY created_at LIMIT 6");
    pick(sim, picked, "busy thread", "SELECT p.id FROM post p WHERE p.body IS NULL ORDER BY (SELECT COUNT(*) FROM comment c"
        + " WHERE c.post_id = p.id) DESC, p.created_at LIMIT 8");
    pick(sim, picked, "everyday", "SELECT id FROM post WHERE body IS NULL ORDER BY created_at LIMIT 200");
    var out = new ArrayList<Fixture>();
    for (var e : picked.entrySet()) {
      if (out.size() >= n) break;
      var packet = builder.thread(e.getKey(), budget);
      if (packet.targets().isEmpty()) continue;
      var expects = new LinkedHashMap<String, Expect>();
      packet.targets().forEach((id, t) -> expects.put(id, new Expect(builder.handle(t.authorId()), t.type(), t.stance(),
          builder.lengthBand(t.authorId()))));
      out.add(new Fixture(e.getKey(), e.getValue(), packet.prefix(), packet.content(), expects));
    }
    return out;
  }

  private static void pick(Simulation sim, Map<String, String> picked, String label, String query) {
    for (String id : sim.sql.query(query, List.of(), r -> r.str("id"))) picked.putIfAbsent(id, label);
  }

  public static void save(List<Fixture> fixtures, Path file) {
    write(file, Json.write(fixtures));
  }

  public static List<Fixture> load(Path file) {
    try {
      return List.of(Json.read(Files.readString(file), Fixture[].class));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ------------------------------------------------------------------------------------- scoring

  /**
   * @param attempts the attempt number of a call's final {@code llm_call} row (2+ = a repair or retry),
   *     or null if the call log isn't available
   */
  public Report score(String alias, LlmGateway gateway, List<Fixture> fixtures, Function<String, Integer> attempts,
      List<Output> outputs) {
    int items = 0, valid = 0, repaired = 0, refusals = 0, truncations = 0, linter = 0, blacklist = 0, isms = 0, inBand = 0;
    long cached = 0, inputs = 0;
    double usd = 0;
    var latencies = new ArrayList<Long>();
    var themes = lib.themeLinter();
    for (var f : fixtures) {
      var r = gateway.call(new LlmRequest<>(Purpose.RENDER_THREAD, f.prefix(), List.of(new Part.Text(f.content())),
          RenderBatch.class, Math.min(8_000, 200 + 160 * f.briefs().size()), Duration.ofSeconds(120), Priority.BACKGROUND));
      latencies.add(r.latency().toMillis());
      if (r.usage() != null) {
        cached += r.usage().cachedInput();
        inputs += r.usage().input() + r.usage().cachedInput() + r.usage().cacheWrite();
        usd += r.usage().costUsd() == null ? 0 : r.usage().costUsd().doubleValue();
      }
      if (r.outcome() == Outcome.REFUSED) refusals++;
      if (r.outcome() == Outcome.TRUNCATED) truncations++;
      Integer attempt = attempts == null || r.callId() == null ? null : attempts.apply(r.callId());
      if (attempt != null && attempt > 1) repaired++;
      var texts = new TreeMap<String, String>();
      items += f.briefs().size();
      if (r.ok() && r.value() != null && r.value().items() != null) {
        var byId = new LinkedHashMap<String, RenderBatch.Item>();
        r.value().items().forEach(i -> byId.putIfAbsent(i.briefId(), i));
        for (var e : f.briefs().entrySet()) {
          var item = byId.get(e.getKey());
          if (item == null || item.text() == null || item.text().isBlank()) continue;
          valid++;
          String text = item.text();
          texts.put(e.getKey(), text);
          if (!themes.hits(text).isEmpty()) blacklist++;
          if (ProseChecks.LLM_ISMS.matcher(text).find()) isms++;
          if (text.split("\\s+").length <= 2 * ProseChecks.bandMax(e.getValue().lengthBand())) inBand++;
          if (ProseChecks.Lexicon.hits(text, ProseChecks.SECRET_TERMS) && f.content().contains("doesn't know about")) linter++;
        }
      }
      outputs.add(new Output(f.id(), alias, r.outcome().name(), r.latency().toMillis(), texts));
    }
    latencies.sort(Long::compare);
    return new Report(alias, fixtures.size(), items, items == 0 ? 0 : valid / (double) items,
        fixtures.isEmpty() ? 0 : repaired / (double) fixtures.size(), refusals, truncations, linter, blacklist, isms,
        valid == 0 ? 0 : inBand / (double) valid, pct(latencies, 0.5), pct(latencies, 0.95),
        inputs == 0 ? 0 : cached / (double) inputs, items == 0 ? 0 : usd / items * 1000);
  }

  private static long pct(List<Long> xs, double q) {
    return xs.isEmpty() ? 0 : xs.get(Math.min(xs.size() - 1, (int) Math.floor(q * xs.size())));
  }

  // ------------------------------------------------------------------------------------- files

  /**
   * Writes {@code report.md}, {@code outputs.json}, the blind {@code side-by-side.md} (models shuffled
   * per fixture, labeled A, B, C…) and {@code key.json} (which label was which) into {@code dir}.
   */
  public static void write(Path dir, List<Report> reports, List<Fixture> fixtures, List<Output> outputs, long seed) {
    var md = new StringBuilder("# Bake-off: render-thread\n\n");
    md.append("| model | fixtures | items | schema-valid | repaired | refusals | truncated | linter | blacklist | LLM-isms"
        + " | length ok | p50 ms | p95 ms | cache hits | $ / 1k items |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (var r : reports) {
      md.append(String.format("| %s | %d | %d | %.0f%% | %.0f%% | %d | %d | %d | %d | %d | %.0f%% | %d | %d | %.0f%% | %.3f |%n",
          r.alias(), r.fixtures(), r.items(), r.schemaValid() * 100, r.repaired() * 100, r.refusals(), r.truncations(),
          r.linterHits(), r.blacklistHits(), r.llmIsms(), r.lengthCompliance() * 100, r.p50Ms(), r.p95Ms(),
          r.cacheHitRate() * 100, r.costPer1000Items()));
    }
    md.append("\nLinter hits are lexicon candidates in packets with a secret, before any judge; read them in the side-by-side.\n");
    write(dir.resolve("report.md"), md.toString());
    write(dir.resolve("outputs.json"), Json.write(outputs));

    var rng = RandomSource.seeded(seed).derive("bakeoff-blind");
    var key = new LinkedHashMap<String, Map<String, String>>();
    var side = new StringBuilder("# Blind side-by-side\n\nFor each thread: the briefs, then each model's take as A, B, C."
        + " Judge voice and authenticity; the key is in key.json.\n");
    var byFixture = new LinkedHashMap<String, List<Output>>();
    outputs.forEach(o -> byFixture.computeIfAbsent(o.fixture(), k -> new ArrayList<>()).add(o));
    for (var f : fixtures) {
      var outs = new ArrayList<>(byFixture.getOrDefault(f.id(), List.of()));
      for (int i = outs.size() - 1; i > 0; i--) java.util.Collections.swap(outs, i, rng.nextInt(i + 1));
      side.append("\n## ").append(f.label()).append(" — ").append(f.id()).append("\n\n");
      f.briefs().forEach((id, x) -> side.append("- ").append(x.author()).append(" ").append(x.kind().toLowerCase())
          .append(x.stance() == null ? "" : " (" + x.stance().toLowerCase() + ")").append('\n'));
      var labels = new LinkedHashMap<String, String>();
      for (int i = 0; i < outs.size(); i++) {
        String label = String.valueOf((char) ('A' + i));
        labels.put(label, outs.get(i).alias());
        side.append("\n**").append(label).append("**").append(outs.get(i).outcome().equals("OK") ? "" : " (" + outs.get(i).outcome() + ")")
            .append('\n');
        for (var e : f.briefs().entrySet()) {
          String text = outs.get(i).texts().get(e.getKey());
          side.append("> ").append(e.getValue().author()).append(": ").append(text == null ? "(nothing)" : text.replace("\n", " "))
              .append('\n');
        }
      }
      key.put(f.id(), labels);
    }
    write(dir.resolve("side-by-side.md"), side.toString());
    write(dir.resolve("key.json"), Json.write(key));
  }

  private static void write(Path file, String text) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, text, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
