package app.radiance.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Login rate limiting (SPEC §5.17): after 5 failed attempts from one address within 5 minutes, further
 * attempts get 429 until the oldest failure is 5 minutes old. A successful login clears the count.
 */
final class LoginLimiter extends OncePerRequestFilter {

  static final int MAX_FAILURES = 5;
  static final long WINDOW_MS = 5 * 60_000L;

  private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return !("POST".equals(req.getMethod()) && "/api/auth/login".equals(req.getRequestURI()));
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var q = failures.get(req.getRemoteAddr());
    if (q != null) {
      synchronized (q) {
        prune(q, System.currentTimeMillis());
        if (q.size() >= MAX_FAILURES) {
          res.setHeader("Retry-After", String.valueOf(Math.max(1, (q.peekFirst() + WINDOW_MS - System.currentTimeMillis()) / 1000)));
          Problems.write(res, HttpStatus.TOO_MANY_REQUESTS, "Too many failed logins. Try again in a few minutes.");
          return;
        }
      }
    }
    chain.doFilter(req, res);
  }

  void failed(HttpServletRequest req) {
    var q = failures.computeIfAbsent(req.getRemoteAddr(), k -> new ArrayDeque<>());
    synchronized (q) {
      q.addLast(System.currentTimeMillis());
      prune(q, System.currentTimeMillis());
    }
  }

  void succeeded(HttpServletRequest req) {
    failures.remove(req.getRemoteAddr());
  }

  private static void prune(Deque<Long> q, long now) {
    while (!q.isEmpty() && q.peekFirst() < now - WINDOW_MS) q.removeFirst();
  }
}
