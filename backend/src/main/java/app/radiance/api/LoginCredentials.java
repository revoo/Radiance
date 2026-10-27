package app.radiance.api;

import org.springframework.security.crypto.password.PasswordEncoder;

/** The resolved single-user login: either from the environment or the offline dev fallback. */
record LoginCredentials(String username, String passwordHash, boolean devFallback) {

  static final String DEV_USERNAME = "r";
  static final String DEV_PASSWORD = "radiance";

  /**
   * Uses the configured credentials when both are present. Otherwise falls back to the dev login
   * if the profile allows it, and fails startup if it doesn't.
   */
  static LoginCredentials resolve(SecurityProperties props, PasswordEncoder encoder) {
    boolean hasUser = props.username() != null && !props.username().isBlank();
    boolean hasHash = props.passwordBcrypt() != null && !props.passwordBcrypt().isBlank();
    if (hasUser && hasHash) {
      return new LoginCredentials(props.username(), props.passwordBcrypt(), false);
    }
    if (props.devLogin()) {
      return new LoginCredentials(DEV_USERNAME, encoder.encode(DEV_PASSWORD), true);
    }
    throw new IllegalStateException(
        "RADIANCE_USERNAME and RADIANCE_PASSWORD_BCRYPT must be set outside the offline profile");
  }
}
