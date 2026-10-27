package app.radiance.world.genesis;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The one-shot {@code genesis} run mode ({@code ./gradlew genesis --args='--seed 7'}):
 *
 * <ul>
 *   <li>{@code --seed N} — skeleton plus flesh-out into {@code data/world-seed.json} (resumes a partial file)
 *   <li>{@code --skeleton-only} — stop after the skeleton
 *   <li>{@code --regenerate <handle>} — rewrite one resident's prose
 *   <li>{@code --import} — import the seed file (after any of the above, or alone)
 * </ul>
 *
 * Does nothing unless the app was started in genesis mode.
 */
@Component
public class GenesisCommand implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(GenesisCommand.class);

  /** Genesis mode: {@code -Dradiance.mode=genesis} (the Gradle task) or {@code genesis} as the first argument. */
  public static boolean isGenesisMode(String[] args) {
    return "genesis".equals(System.getProperty("radiance.mode"))
        || (args.length > 0 && "genesis".equals(args[0]));
  }

  private final GenesisService genesis;
  private final ConfigurableApplicationContext context;

  GenesisCommand(GenesisService genesis, ConfigurableApplicationContext context) {
    this.genesis = genesis;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    String[] args = arguments.getSourceArgs();
    if (!isGenesisMode(args)) return;
    int code = 0;
    try {
      execute(Arrays.asList(args));
    } catch (Exception e) {
      log.error("Genesis failed: {}", e.getMessage(), e);
      code = 1;
    }
    int exit = code;
    System.exit(SpringApplication.exit(context, () -> exit));
  }

  void execute(List<String> args) throws InterruptedException {
    String seed = value(args, "--seed");
    String regenerate = value(args, "--regenerate");
    if (seed != null) {
      var summary = genesis.preview(Long.parseLong(seed), args.contains("--skeleton-only"));
      log.info("Genesis wrote {}: {} residents ({} fleshed out), {} relationships, {} arcs, {} entities",
          summary.file(), summary.residents(), summary.fleshedOut(), summary.relationships(), summary.arcs(),
          summary.entities());
    }
    if (regenerate != null) {
      genesis.regenerate(regenerate);
      log.info("Genesis regenerated @{} in {}", regenerate, genesis.seedFile());
    }
    if (args.contains("--import")) {
      var result = genesis.commit();
      log.info("Genesis imported: {} residents, {} relationships, {} arcs, {} entities, {} facts",
          result.residents(), result.relationships(), result.arcs(), result.entities(), result.facts());
    }
    if (seed == null && regenerate == null && !args.contains("--import")) {
      log.info("Usage: genesis --seed N [--skeleton-only] [--import] | --regenerate <handle> | --import");
    }
  }

  /** {@code --name value} or {@code --name=value}. */
  private static String value(List<String> args, String name) {
    for (int i = 0; i < args.size(); i++) {
      String a = args.get(i);
      if (a.startsWith(name + "=")) return a.substring(name.length() + 1);
      if (a.equals(name) && i + 1 < args.size()) return args.get(i + 1);
    }
    return null;
  }
}
