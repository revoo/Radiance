package app.radiance.narrative;

import app.radiance.content.ContentLibrary;
import app.radiance.llm.DefaultLlmGateway;
import app.radiance.llm.LlmProperties;
import app.radiance.llm.Purpose;
import app.radiance.llm.RouteTable;
import app.radiance.persistence.Sql;
import app.radiance.sim.Persona;
import app.radiance.sim.Simulation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The {@code llm-bakeoff} run mode (SPEC §5.10): {@code ./gradlew llm-bakeoff
 * --args='--purpose=render-thread --models=haiku,<candidate> --fixtures=50'}. Fixtures come from an
 * offline seed-7 world and are saved, so every run and re-run scores the same packets; each model gets
 * the purpose's route to itself, with no fallback. Reports go to {@code data/bakeoffs/<time>/}.
 */
@Component
public class BakeoffCommand implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BakeoffCommand.class);

  public static boolean isBakeoffMode(String[] args) {
    return "bakeoff".equals(System.getProperty("radiance.mode")) || (args.length > 0 && "bakeoff".equals(args[0]));
  }

  private final DefaultLlmGateway gateway;
  private final LlmProperties props;
  private final Sql sql;
  private final ContentLibrary lib;
  private final ConfigurableApplicationContext context;

  BakeoffCommand(DefaultLlmGateway gateway, LlmProperties props, Sql sql, ContentLibrary lib,
      ConfigurableApplicationContext context) {
    this.gateway = gateway;
    this.props = props;
    this.sql = sql;
    this.lib = lib;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    String[] args = arguments.getSourceArgs();
    if (!isBakeoffMode(args)) return;
    int code = 0;
    try {
      execute(Arrays.asList(args));
    } catch (Exception e) {
      log.error("Bake-off failed: {}", e.getMessage(), e);
      code = 1;
    }
    int exit = code;
    System.exit(SpringApplication.exit(context, () -> exit));
  }

  void execute(List<String> args) throws Exception {
    String purpose = value(args, "--purpose", "render-thread");
    if (!purpose.equals(Purpose.RENDER_THREAD.key())) {
      throw new IllegalArgumentException("only --purpose=render-thread has fixtures so far");
    }
    var models = List.of(value(args, "--models", "haiku").split(","));
    int n = Integer.parseInt(value(args, "--fixtures", "50"));
    long seed = Long.parseLong(value(args, "--seed", "7"));
    var root = Path.of("data", "bakeoffs");
    var fixturesFile = root.resolve("fixtures-seed" + seed + "-" + n + ".json");
    List<Bakeoff.Fixture> fixtures;
    if (Files.exists(fixturesFile)) {
      fixtures = Bakeoff.load(fixturesFile);
    } else {
      try (var sim = new Simulation(Simulation.Options.of(seed, 3, Persona.WARM, root.resolve("world"))).run()) {
        fixtures = Bakeoff.fixtures(sim, n);
      }
      Bakeoff.save(fixtures, fixturesFile);
    }
    log.info("Bake-off: {} fixtures, models {}", fixtures.size(), models);
    var bakeoff = new Bakeoff(lib);
    var reports = new ArrayList<Bakeoff.Report>();
    var outputs = new ArrayList<Bakeoff.Output>();
    var original = RouteTable.build(props);
    try {
      for (String alias : models) {
        if (!props.models().containsKey(alias)) throw new IllegalArgumentException("unknown model alias '" + alias + "'");
        var routes = new LinkedHashMap<>(props.routes());
        routes.put(purpose, new LlmProperties.Route(alias, List.of()));
        gateway.reroute(RouteTable.build(new LlmProperties(props.mode(), props.providers(), props.models(), routes,
            props.maxPromptTokens(), props.budgets(), props.storePrompts(), props.promptStoreDir())));
        var report = bakeoff.score(alias, gateway, fixtures, id -> sql.queryOne("SELECT attempt FROM llm_call WHERE id = ?",
            List.of(id), r -> (int) r.lng("attempt")).orElse(null), outputs);
        reports.add(report);
        log.info("Bake-off {}: {}", alias, report);
      }
    } finally {
      gateway.reroute(original);
    }
    var dir = root.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
    Bakeoff.write(dir, reports, fixtures, outputs, seed);
    log.info("Bake-off wrote {}", dir.toAbsolutePath());
  }

  static String value(List<String> args, String name, String fallback) {
    for (int i = 0; i < args.size(); i++) {
      String a = args.get(i);
      if (a.startsWith(name + "=")) return a.substring(name.length() + 1);
      if (a.equals(name) && i + 1 < args.size()) return args.get(i + 1);
    }
    return fallback;
  }
}
