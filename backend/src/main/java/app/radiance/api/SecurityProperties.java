package app.radiance.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The single local user (SPEC §5.17). Credentials come from {@code RADIANCE_USERNAME} and
 * {@code RADIANCE_PASSWORD_BCRYPT}. Only a profile that sets {@code dev-login} (the {@code offline}
 * profile) may fall back to a built-in development login.
 */
@ConfigurationProperties("radiance.security")
public record SecurityProperties(String username, String passwordBcrypt, boolean devLogin) {}
