package app.radiance.llm;

import app.radiance.kernel.RandomSource;
import app.radiance.kernel.Ulid;
import app.radiance.kernel.WallClock;
import app.radiance.llm.output.InvalidOutputException;
import app.radiance.llm.output.OutputParser;
import app.radiance.llm.output.OutputSchema;
import app.radiance.llm.provider.LlmProvider;
import app.radiance.llm.provider.ProviderRequest;
import app.radiance.llm.provider.ProviderResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes each purpose to its models and enforces everything that must hold whichever model answers
 * (SPEC §5.10, §5.15): budgets, validation with one repair call, retries for transient errors, one
 * retry with a higher limit on truncation, fallbacks in route order, a per-provider circuit breaker and
 * priority-ordered concurrency caps, and metering of every attempt.
 */
public final class DefaultLlmGateway implements LlmGateway {

  private static final Logger log = LoggerFactory.getLogger(DefaultLlmGateway.class);

  static final int TRANSIENT_RETRIES = 2;
  static final long BACKOFF_MS = 500;
  static final long DEFAULT_RATE_LIMIT_WAIT_MS = 60_000;
  static final double DEFAULT_CHARS_PER_TOKEN = 3.0;

  /** Sleeps between transient retries; tests pass a no-op. */
  public interface Backoff {
    void sleep(long ms) throws InterruptedException;
  }

  private volatile RouteTable routes;
  private final int maxPromptTokens;
  private final Map<String, LlmProvider> providers;
  private final Budget budget;
  private final LlmCallLog callLog;
  private final MeterRegistry meters;
  private final WallClock clock;
  private final Backoff backoff;
  private final Function<String, String> promptNotes;
  private final CircuitBreaker breaker;
  private final Map<String, PrioritySemaphore> slots = new ConcurrentHashMap<>();
  private final Map<String, Double> charsPerToken = new ConcurrentHashMap<>();
  private final RandomSource ids = RandomSource.seeded(new SecureRandom().nextLong());

  public DefaultLlmGateway(
      RouteTable routes,
      int maxPromptTokens,
      Map<String, LlmProvider> providers,
      Budget budget,
      LlmCallLog callLog,
      MeterRegistry meters,
      WallClock clock,
      Backoff backoff,
      Function<String, String> promptNotes) {
    this.routes = routes;
    this.maxPromptTokens = maxPromptTokens;
    this.providers = Map.copyOf(providers);
    this.budget = budget;
    this.callLog = callLog;
    this.meters = meters;
    this.clock = clock;
    this.backoff = backoff;
    this.promptNotes = promptNotes;
    this.breaker = new CircuitBreaker(clock);
  }

  /** Swaps in new routes (live-tuned from the Director's console, M13). */
  public void reroute(RouteTable routes) {
    this.routes = routes;
  }

  @Override
  public PromptBudget budgetFor(Purpose purpose) {
    var targets = routes.targets(purpose);
    int max = maxPromptTokens;
    for (var t : targets) max = Math.min(max, t.model().capabilities().maxContext());
    double cpt = charsPerToken.getOrDefault(targets.getFirst().providerName(), DEFAULT_CHARS_PER_TOKEN);
    return new PromptBudget(max, cpt);
  }

  @Override
  public int degradationLevel() {
    return Degradation.level(budget.pressure());
  }

  @Override
  public <T> List<LlmResult<T>> callAll(List<LlmRequest<T>> requests) {
    if (requests.isEmpty()) return List.of();
    var results = new ArrayList<LlmResult<T>>(requests.size());
    results.add(call(requests.getFirst())); // warms the provider's prefix cache
    if (requests.size() == 1) return results;
    try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<LlmResult<T>>>();
      for (var r : requests.subList(1, requests.size())) futures.add(exec.submit(() -> call(r)));
      for (var f : futures) results.add(join(f));
    }
    return results;
  }

  private static <T> T join(Future<T> f) {
    try {
      return f.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException re) throw re;
      throw new IllegalStateException(e.getCause());
    }
  }

  @Override
  public <T> LlmResult<T> call(LlmRequest<T> request) {
    long start = clock.nowMs();
    var targets =
        request.priority() == Priority.LIVE && degradationLevel() >= Degradation.CHEAPEST_LIVE
            ? routes.cheapestFirst(request.purpose())
            : routes.targets(request.purpose());

    Usage total = Usage.ZERO;
    Outcome last = Outcome.ERROR;
    String lastCallId = null;
    BudgetExhaustedException parked = null;
    int attempt = 0;

    for (var target : targets) {
      if (breaker.isOpen(target.providerName())) {
        log.debug("LLM: provider {} circuit open; skipping {}", target.providerName(), target.alias());
        continue;
      }
      try {
        budget.admit(request.priority(), target.providerName(), estimateUsd(request, target),
            estimateTokens(request, target));
      } catch (BudgetExhaustedException e) {
        if (e.windowId().startsWith("usd:")) throw e; // spending caps apply to every model
        parked = parked == null || e.resumeAt() < parked.resumeAt() ? e : parked;
        continue; // this provider's quota is out; try the next model
      }

      var current = request;
      String repairNote = null;
      int transientRetries = 0;
      boolean truncationRetried = false;
      while (true) {
        attempt++;
        var call = attempt(target, current, repairNote, attempt);
        total = total.plus(call.usage());
        last = call.response().outcome();
        lastCallId = call.callId();
        var resp = call.response();

        if (resp.outcome() == Outcome.OK) {
          try {
            T value = OutputParser.parse(resp.text(), request.output());
            return new LlmResult<>(Outcome.OK, value, total, elapsed(start), lastCallId);
          } catch (InvalidOutputException e) {
            last = Outcome.INVALID_OUTPUT;
            if (repairNote != null) break;
            repairNote = repairNote(resp.text(), e.getMessage());
            continue;
          }
        }
        if (resp.outcome() == Outcome.INVALID_OUTPUT) {
          if (repairNote != null) break;
          repairNote = repairNote(resp.text(), resp.error());
          continue;
        }
        if (resp.outcome() == Outcome.TRUNCATED && !truncationRetried) {
          truncationRetried = true;
          current = current.withMaxOutputTokens(current.maxOutputTokens() * 2);
          continue;
        }
        if (resp.outcome() == Outcome.RATE_LIMITED) {
          long wait = resp.retryAfterMs() != null ? resp.retryAfterMs() : DEFAULT_RATE_LIMIT_WAIT_MS;
          budget.closeProvider(target.providerName(), clock.nowMs() + wait);
          parked = new BudgetExhaustedException(target.providerName() + ":closed", clock.nowMs() + wait);
          break;
        }
        if (resp.outcome().isTransient() && transientRetries < TRANSIENT_RETRIES
            && !breaker.isOpen(target.providerName())) {
          sleep(BACKOFF_MS << transientRetries++);
          continue;
        }
        break; // REFUSED, a second TRUNCATED, or retries used up: next model
      }
    }

    // Non-live work parks when quota, not the models, stopped it: nothing was sent, or the last answer
    // was a rate limit. Live calls get the failed result instead and degrade in the domain.
    if (parked != null && request.priority() != Priority.LIVE
        && (lastCallId == null || last == Outcome.RATE_LIMITED)) {
      throw parked;
    }
    return new LlmResult<>(last, null, total, elapsed(start), lastCallId);
  }

  private record Attempt(ProviderResponse response, Usage usage, String callId) {}

  private <T> Attempt attempt(
      RouteTable.Target target, LlmRequest<T> request, String repairNote, int attemptNo) {
    var prefix = new ArrayList<>(request.prefix());
    if (target.model().promptNotes() != null) {
      prefix.add(new Block(promptNotes.apply(target.model().promptNotes())));
    }
    var content = new ArrayList<>(request.content());
    if (repairNote != null) content.add(Part.text(repairNote));
    var providerRequest =
        new ProviderRequest(
            request.purpose(),
            target.alias(),
            target.model(),
            target.provider(),
            prefix,
            content,
            request.output(),
            OutputSchema.of(request.output()),
            OutputSchema.name(request.output()),
            request.maxOutputTokens(),
            request.timeout());

    var provider = providers.get(target.providerName());
    var slot = slots.computeIfAbsent(target.providerName(),
        p -> new PrioritySemaphore(target.provider().maxConcurrency()));
    long t0 = clock.nowMs();
    ProviderResponse resp;
    try {
      slot.acquire(request.priority());
      try {
        resp = provider.complete(providerRequest);
      } finally {
        slot.release();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      resp = ProviderResponse.failure(Outcome.ERROR, target.model().id(), "interrupted");
    } catch (RuntimeException e) {
      log.warn("LLM: adapter for {} threw", target.alias(), e);
      resp = ProviderResponse.failure(Outcome.ERROR, target.model().id(), e.toString());
    }
    long latency = clock.nowMs() - t0;

    if (resp.outcome() == Outcome.ERROR || resp.outcome() == Outcome.TIMEOUT) {
      breaker.failure(target.providerName());
    } else {
      breaker.success(target.providerName());
    }

    var tokens = resp.tokens() == null ? ProviderResponse.TokenCounts.ZERO : resp.tokens();
    double cost = cost(target.model().pricing(), tokens);
    var usage = new Usage(tokens.input(), tokens.cachedInput(), tokens.cacheWrite(), tokens.output(),
        tokens.reasoning(), BigDecimal.valueOf(cost));
    budget.record(request.priority(), target.providerName(), cost, usage.totalTokens());
    calibrate(target.providerName(), request, tokens);

    String id;
    synchronized (ids) {
      id = Ulid.next(clock.nowMs(), ids);
    }
    callLog.record(new LlmCallLog.Entry(id, request.purpose(), target.providerName(), target.alias(),
        resp.modelId() != null ? resp.modelId() : target.model().id(), attemptNo, resp.outcome(),
        tokens.input(), tokens.cachedInput(), tokens.cacheWrite(), tokens.output(), tokens.reasoning(),
        cost, latency, resp.error(), null, clock.nowMs()));
    meter(request.purpose(), target, resp.outcome(), tokens, cost, latency);
    return new Attempt(resp, usage, id);
  }

  static double cost(LlmProperties.Pricing p, ProviderResponse.TokenCounts t) {
    return (t.input() * p.input() + t.cachedInput() * p.cachedInput()
        + t.cacheWrite() * p.cacheWrite() + t.output() * p.output()) / 1_000_000.0;
  }

  /** Upper bound: the whole prompt uncached plus the full output limit. */
  private double estimateUsd(LlmRequest<?> r, RouteTable.Target t) {
    var p = t.model().pricing();
    return (estimatePromptTokens(r, t) * p.input() + r.maxOutputTokens() * p.output()) / 1_000_000.0;
  }

  private long estimateTokens(LlmRequest<?> r, RouteTable.Target t) {
    return estimatePromptTokens(r, t) + r.maxOutputTokens();
  }

  private long estimatePromptTokens(LlmRequest<?> r, RouteTable.Target t) {
    return (long) Math.ceil(r.promptChars()
        / charsPerToken.getOrDefault(t.providerName(), DEFAULT_CHARS_PER_TOKEN));
  }

  /** Learns each provider's characters per token from what it bills (text-only prompts). */
  private void calibrate(String provider, LlmRequest<?> r, ProviderResponse.TokenCounts t) {
    int promptTokens = t.input() + t.cachedInput() + t.cacheWrite();
    if (promptTokens <= 0 || r.content().stream().anyMatch(p -> p instanceof Part.Image)) return;
    double observed = (double) r.promptChars() / promptTokens;
    charsPerToken.merge(provider, observed, (old, obs) -> old * 0.9 + obs * 0.1);
  }

  private static String repairNote(String previous, String errors) {
    return "Your previous reply could not be used: " + errors + "\nPrevious reply:\n"
        + (previous == null ? "(none)" : previous)
        + "\nReply again with only the corrected JSON object, matching the schema.";
  }

  private void meter(Purpose purpose, RouteTable.Target t, Outcome outcome,
      ProviderResponse.TokenCounts tokens, double cost, long latencyMs) {
    var tags = Tags.of("purpose", purpose.key(), "provider", t.providerName(), "alias", t.alias());
    meters.counter("radiance.llm.calls", tags.and("outcome", outcome.name())).increment();
    meters.counter("radiance.llm.cost.usd", tags).increment(cost);
    meters.counter("radiance.llm.tokens", tags.and("kind", "input")).increment(tokens.input());
    meters.counter("radiance.llm.tokens", tags.and("kind", "cached")).increment(tokens.cachedInput());
    meters.counter("radiance.llm.tokens", tags.and("kind", "cache_write")).increment(tokens.cacheWrite());
    meters.counter("radiance.llm.tokens", tags.and("kind", "output")).increment(tokens.output());
    meters.timer("radiance.llm.latency", tags).record(Duration.ofMillis(latencyMs));
  }

  private void sleep(long ms) {
    try {
      backoff.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Duration elapsed(long start) {
    return Duration.ofMillis(clock.nowMs() - start);
  }
}
