package app.radiance.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Single-user web security (SPEC §5.17). The SPA login flow and CSRF wiring arrive in M7. Only for
 * the web app: one-shot command modes (genesis, and batch from M6) run without a web server.
 */
@Configuration
@ConditionalOnWebApplication
class WebSecurityConfig {

  private static final Logger log = LoggerFactory.getLogger(WebSecurityConfig.class);

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService userDetailsService(SecurityProperties props, PasswordEncoder encoder) {
    var creds = LoginCredentials.resolve(props, encoder);
    if (creds.devFallback()) {
      log.warn(
          "!!! Using the built-in dev login ({} / {}). Set RADIANCE_USERNAME and "
              + "RADIANCE_PASSWORD_BCRYPT for anything but local offline play. !!!",
          LoginCredentials.DEV_USERNAME,
          LoginCredentials.DEV_PASSWORD);
    }
    return new InMemoryUserDetailsManager(
        User.withUsername(creds.username()).password(creds.passwordHash()).roles("USER").build());
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(Customizer.withDefaults())
        .build();
  }
}
