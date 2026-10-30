package app.radiance.api;

import app.radiance.persistence.LeaseLostException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors the SPA can act on (SPEC §7, RFC 9457). Writes while this process doesn't hold the writer
 * lease — right after a restart, until a crashed predecessor's lease runs out — are a 503 to retry,
 * not a server error.
 */
@ConditionalOnWebApplication
@RestControllerAdvice
class ApiErrors {

  @ExceptionHandler(LeaseLostException.class)
  ResponseEntity<ProblemDetail> lease(LeaseLostException e) {
    var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
        "The world is starting up. Try again in a few seconds.");
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5").body(problem);
  }
}
