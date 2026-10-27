package app.radiance.world;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param seed the seed of the placeholder world row created at first boot; genesis (M3) replaces it
 */
@ConfigurationProperties("radiance.world")
public record WorldProperties(
    String name,
    String timezone,
    String homeMetro,
    String networkName,
    String roster,
    long seed) {}
