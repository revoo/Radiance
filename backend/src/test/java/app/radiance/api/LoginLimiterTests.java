package app.radiance.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LoginLimiterTests {

  /** A public host sees endless addresses once each: the limiter's memory stays bounded. */
  @Test
  void itForgetsAddressesInsteadOfGrowingForever() {
    var limiter = new LoginLimiter();
    for (int i = 0; i < LoginLimiter.MAX_ADDRESSES * 2 + 5; i++) {
      var req = new MockHttpServletRequest();
      req.setRemoteAddr("10." + (i >> 16) + "." + ((i >> 8) & 255) + "." + (i & 255));
      limiter.failed(req);
      assertThat(limiter.tracked()).isLessThanOrEqualTo(LoginLimiter.MAX_ADDRESSES);
    }
  }

  @Test
  void aSuccessfulLoginClearsTheCount() {
    var limiter = new LoginLimiter();
    var req = new MockHttpServletRequest();
    limiter.failed(req);
    assertThat(limiter.tracked()).isEqualTo(1);
    limiter.succeeded(req);
    assertThat(limiter.tracked()).isZero();
  }
}
