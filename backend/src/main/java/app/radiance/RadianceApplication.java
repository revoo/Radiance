package app.radiance;

import app.radiance.world.genesis.GenesisCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RadianceApplication {

  public static void main(String[] args) {
    var app = new SpringApplication(RadianceApplication.class);
    // One-shot command modes run without a web server (SPEC §5.17 run modes).
    if (GenesisCommand.isGenesisMode(args)) app.setWebApplicationType(WebApplicationType.NONE);
    app.run(args);
  }
}
