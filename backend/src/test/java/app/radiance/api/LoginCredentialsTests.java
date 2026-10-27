package app.radiance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class LoginCredentialsTests {

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

  @Test
  void configuredCredentialsWin() {
    var hash = encoder.encode("s3cret");
    var creds = LoginCredentials.resolve(new SecurityProperties("russ", hash, true), encoder);
    assertThat(creds.username()).isEqualTo("russ");
    assertThat(creds.passwordHash()).isEqualTo(hash);
    assertThat(creds.devFallback()).isFalse();
  }

  @Test
  void devLoginFallsBackWhenAllowed() {
    var creds = LoginCredentials.resolve(new SecurityProperties("", null, true), encoder);
    assertThat(creds.username()).isEqualTo(LoginCredentials.DEV_USERNAME);
    assertThat(encoder.matches(LoginCredentials.DEV_PASSWORD, creds.passwordHash())).isTrue();
    assertThat(creds.devFallback()).isTrue();
  }

  @Test
  void missingCredentialsFailOutsideOffline() {
    assertThatThrownBy(
            () -> LoginCredentials.resolve(new SecurityProperties(null, null, false), encoder))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RADIANCE_USERNAME");
  }
}
