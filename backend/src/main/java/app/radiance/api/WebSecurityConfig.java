package app.radiance.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Single-user web security (SPEC §5.17): the SPA logs in with a form POST to {@code /api/auth/login}
 * and gets JSON back, not a redirect; the session cookie is HttpOnly and SameSite=Lax (Secure outside
 * localhost, from {@code server.servlet.session.cookie.secure}); a cookie-based CSRF token guards every
 * write; failed logins are rate limited. Only for the web app: one-shot command modes run without one.
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
  LoginLimiter loginLimiter() {
    return new LoginLimiter();
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, LoginLimiter limiter) throws Exception {
    return http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**", "/api/auth/login")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(csrf -> csrf.spa())
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        .addFilterBefore(limiter, UsernamePasswordAuthenticationFilter.class)
        .formLogin(form -> form.loginProcessingUrl("/api/auth/login")
            .successHandler((req, res, auth) -> {
              limiter.succeeded(req);
              res.setStatus(HttpStatus.OK.value());
              res.setContentType("application/json");
              res.getWriter().write("{\"username\":\"" + auth.getName().replace("\"", "") + "\"}");
            })
            .failureHandler((req, res, e) -> {
              limiter.failed(req);
              Problems.write(res, HttpStatus.UNAUTHORIZED, "Wrong username or password.");
            }))
        .logout(out -> out.logoutUrl("/api/auth/logout")
            .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
        .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .build();
  }

  /** Renders the CSRF cookie on every response, so the SPA has a token before its first write. */
  static final class CsrfCookieFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
      if (req.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) token.getToken();
      chain.doFilter(req, res);
    }
  }
}
