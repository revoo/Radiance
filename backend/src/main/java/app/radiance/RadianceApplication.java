package app.radiance;

import app.radiance.batch.BatchCommand;
import app.radiance.narrative.BakeoffCommand;
import app.radiance.world.genesis.GenesisCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RadianceApplication {

  public static void main(String[] args) {
    configure(new SpringApplication(RadianceApplication.class), args).run(args);
  }

  /**
   * One-shot command modes run without a web server (SPEC §5.17 run modes). A mode named by the first
   * argument ({@code docker run IMAGE genesis ...}) counts the same as one the Gradle tasks set: nothing
   * in-process may be scheduled alongside it.
   */
  static SpringApplication configure(SpringApplication app, String[] args) {
    if (GenesisCommand.isGenesisMode(args) || BakeoffCommand.isBakeoffMode(args) || BatchCommand.isBatchMode(args)) {
      app.setWebApplicationType(WebApplicationType.NONE);
      if (System.getProperty("radiance.mode") == null) System.setProperty("radiance.mode", args[0]);
    }
    return app;
  }
}
