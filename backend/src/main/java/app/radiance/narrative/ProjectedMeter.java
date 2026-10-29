package app.radiance.narrative;

import app.radiance.kernel.Json;
import app.radiance.llm.LlmGateway;
import app.radiance.llm.LlmRequest;
import app.radiance.llm.LlmResult;
import app.radiance.llm.Part;
import app.radiance.llm.PromptBudget;
import app.radiance.llm.Purpose;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a run would cost on a real model (SPEC §5.15), for offline runs: wraps a gateway (usually FakeLlm)
 * and prices every call's estimated tokens at an alias's prices, treating a repeated prefix as a cache
 * read, as Anthropic and other caching providers bill it. Also counts the distinct prefixes per purpose:
 * one means the prefix is byte-identical across every call (SPEC §10).
 */
public final class ProjectedMeter implements LlmGateway {

  /** USD per million tokens. */
  public record Pricing(double input, double cachedInput, double cacheWrite, double output) {
    /** The default {@code haiku} alias (SPEC §9). */
    public static final Pricing HAIKU = new Pricing(0.10, 0.01, 0.125, 0.50);
  }

  public static final class Totals {
    public int calls;
    public long input;
    public long cachedInput;
    public long cacheWrite;
    public long output;
    public double usd;
    public int cacheHits;
    public final Set<String> prefixes = new HashSet<>();

    /** Share of calls whose prefix was already cached. */
    public double hitRate() {
      return calls == 0 ? 0 : cacheHits / (double) calls;
    }

    /** Share of input tokens read from the cache. */
    public double cachedShare() {
      long all = input + cachedInput + cacheWrite;
      return all == 0 ? 0 : cachedInput / (double) all;
    }
  }

  private final LlmGateway delegate;
  private final Pricing pricing;
  private final double charsPerToken;
  private final Map<Purpose, Totals> totals = new EnumMap<>(Purpose.class);

  public ProjectedMeter(LlmGateway delegate, Pricing pricing, double charsPerToken) {
    this.delegate = delegate;
    this.pricing = pricing;
    this.charsPerToken = charsPerToken;
  }

  @Override
  public synchronized <T> LlmResult<T> call(LlmRequest<T> request) {
    var r = delegate.call(request);
    var t = totals.computeIfAbsent(request.purpose(), p -> new Totals());
    int prefixChars = request.prefix().stream().mapToInt(b -> b.text().length()).sum();
    int contentChars = request.content().stream().mapToInt(p -> p instanceof Part.Text x ? x.text().length() : 0).sum();
    long prefixTokens = Math.round(prefixChars / charsPerToken);
    long contentTokens = Math.round(contentChars / charsPerToken);
    long outTokens = r.value() == null ? 0 : Math.round(Json.write(r.value()).length() / charsPerToken);
    String key = hash(request);
    boolean hit = prefixTokens > 0 && !t.prefixes.add(key);
    t.calls++;
    if (hit) {
      t.cacheHits++;
      t.cachedInput += prefixTokens;
    } else {
      t.cacheWrite += prefixTokens;
    }
    t.input += contentTokens;
    t.output += outTokens;
    t.usd += ((hit ? 0 : prefixTokens * pricing.cacheWrite()) + (hit ? prefixTokens * pricing.cachedInput() : 0)
        + contentTokens * pricing.input() + outTokens * pricing.output()) / 1_000_000.0;
    return r;
  }

  @Override
  public <T> List<LlmResult<T>> callAll(List<LlmRequest<T>> requests) {
    return requests.stream().map(this::call).toList();
  }

  @Override
  public PromptBudget budgetFor(Purpose purpose) {
    return delegate.budgetFor(purpose);
  }

  @Override
  public int degradationLevel() {
    return delegate.degradationLevel();
  }

  public synchronized Map<Purpose, Totals> totals() {
    return new TreeMap<>(totals);
  }

  public synchronized double usd() {
    return totals.values().stream().mapToDouble(x -> x.usd).sum();
  }

  public synchronized void reset() {
    totals.clear();
  }

  private static String hash(LlmRequest<?> r) {
    try {
      var d = MessageDigest.getInstance("SHA-256");
      for (var b : r.prefix()) d.update(b.text().getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(d.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
