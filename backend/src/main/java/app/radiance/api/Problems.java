package app.radiance.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** RFC 9457 problem details (SPEC §7), for filters that answer before MVC does, and a shorthand for controllers. */
final class Problems {

  private Problems() {}

  static void write(HttpServletResponse res, HttpStatus status, String detail) throws IOException {
    res.setStatus(status.value());
    res.setContentType("application/problem+json");
    res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":"
        + status.value() + ",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
  }

  static ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + " not found");
  }

  static ResponseStatusException bad(String detail) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
  }
}
