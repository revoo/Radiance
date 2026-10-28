package app.radiance.sim;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The {@code simulate} task (SPEC §10): {@code ./gradlew simulate --args='--days=30 --seed=42
 * --user=scripted:warm [--dump-day=3] [--out=data/sim]'}. Offline, no Spring, no keys. Writes the feel
 * report (and a day's script, if asked) to {@code --out} and prints the report.
 */
public final class SimulateMain {

  private SimulateMain() {}

  public static void main(String[] args) throws Exception {
    long seed = Long.parseLong(arg(args, "seed", "42"));
    int days = Integer.parseInt(arg(args, "days", "30"));
    var persona = Persona.parse(arg(args, "user", "scripted:warm"));
    var out = Path.of(arg(args, "out", "data/sim"));
    String dump = arg(args, "dump-day", null);
    var options = new Simulation.Options(seed, days, persona, out, Knobs.defaults(), ContinuityAuditor.Mode.RECORD);
    try (var sim = new Simulation(options).run()) {
      var report = FeelReport.of(sim);
      String text = report.render(sim);
      Files.writeString(out.resolve("feel-report.txt"), text, StandardCharsets.UTF_8);
      System.out.println(text);
      if (dump != null) {
        var date = Simulation.START.plusDays(Integer.parseInt(dump));
        Files.writeString(out.resolve("script-" + date + ".txt"), ScriptDump.day(sim.sql, sim.engine.world(), date),
            StandardCharsets.UTF_8);
        System.out.println("Wrote " + out.resolve("script-" + date + ".txt"));
      }
      for (var d : sim.days) {
        if (!d.findings().isEmpty()) System.out.println(d.date() + " findings: " + d.findings());
      }
      if (!report.ok()) System.out.println("Some bands are outside their range (see ✘ above).");
    }
  }

  static String arg(String[] args, String name, String fallback) {
    for (String a : args) {
      if (a.startsWith("--" + name + "=")) return a.substring(name.length() + 3);
    }
    return fallback;
  }
}
