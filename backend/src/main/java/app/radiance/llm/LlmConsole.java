package app.radiance.llm;

import app.radiance.llm.budget.BudgetLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What the Director's console sees and changes in the LLM layer (SPEC §5.16): routes, budgets and stored prompts. Everything
 * provider-shaped stays behind this door.
 */
public final class LlmConsole {

  /** A model alias a route can name. */
  public record ModelView(String alias, String provider, boolean available, boolean vision, double inputPrice, double outputPrice) {}

  /** A budget window right now. */
  public record Window(String id, double used, double limit, long resetsAt, Long closedUntil) {}

  private final LlmProperties props;
  private final DefaultLlmGateway gateway;
  private final BudgetLedger ledger;
  private final Path promptDir;
  private volatile Map<String, LlmProperties.Route> routes;
  private volatile LlmProperties.Budgets budgets;

  LlmConsole(LlmProperties props, DefaultLlmGateway gateway, BudgetLedger ledger) {
    this.props = props;
    this.gateway = gateway;
    this.ledger = ledger;
    this.promptDir = Path.of(props.promptStoreDir()).toAbsolutePath().normalize();
    this.routes = new TreeMap<>(props.routes());
    this.budgets = props.budgets();
  }

  public Map<String, LlmProperties.Route> routes() {
    return routes;
  }

  public List<ModelView> models() {
    return new TreeMap<>(props.models()).entrySet().stream().map(e -> {
      var m = e.getValue();
      var provider = props.providers().get(m.provider());
      boolean available = props.mode() == LlmProperties.Mode.FAKE || (provider != null && provider.hasKey());
      return new ModelView(e.getKey(), m.provider(), available, m.capabilities().vision(), m.pricing().input(), m.pricing().output());
    }).toList();
  }

  /** The routes in config, with {@code overrides} on top. Throws {@link IllegalStateException} if they can't work. */
  public Map<String, LlmProperties.Route> checked(Map<String, LlmProperties.Route> overrides) {
    var merged = new LinkedHashMap<>(props.routes());
    merged.putAll(overrides);
    table(merged);
    return merged;
  }

  /** Swaps the routes in: the next call uses them. */
  public void reroute(Map<String, LlmProperties.Route> merged) {
    gateway.reroute(table(merged));
    routes = new TreeMap<>(merged);
  }

  private RouteTable table(Map<String, LlmProperties.Route> merged) {
    return RouteTable.build(new LlmProperties(props.mode(), props.providers(), props.models(), merged, props.maxPromptTokens(),
        props.budgets(), props.storePrompts(), props.promptStoreDir()));
  }

  public LlmProperties.Budgets budgets() {
    return budgets;
  }

  public void setBudgets(LlmProperties.Budgets budgets) {
    this.budgets = budgets;
    ledger.setLimits(budgets);
  }

  public List<Window> windows() {
    return ledger.snapshot().stream().map(r -> new Window(r.id(), r.used(), r.limit(), r.resetsAt(), r.closedUntil())).toList();
  }

  /** Opens a batch's own spending window (SPEC §5.15), at today's per-batch limit. */
  public void openBatch() {
    ledger.openBatch(budgets.perBatchUsd());
  }

  public void closeBatch() {
    ledger.closeBatch();
  }

  /** A stored prompt and its response, by {@code llm_call.prompt_ref}; never anything outside the prompt folder. */
  public Optional<String> prompt(String ref) {
    Path file = promptDir.resolve(ref).normalize();
    if (!file.startsWith(promptDir) || !Files.isRegularFile(file)) return Optional.empty();
    try {
      return Optional.of(Files.readString(file));
    } catch (IOException e) {
      return Optional.empty();
    }
  }
}
