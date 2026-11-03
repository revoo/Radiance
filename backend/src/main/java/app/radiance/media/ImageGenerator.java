package app.radiance.media;

/** Makes pictures from a prompt (SPEC §5.13). The same prompt and seed should give a recognizably similar picture. */
public interface ImageGenerator {

  record Generated(byte[] bytes, String mime) {}

  /** The provider says no for now (a daily allocation, a rate limit): try again on a later run. */
  final class QuotaExceeded extends RuntimeException {
    public QuotaExceeded(String message) {
      super(message);
    }
  }

  /** @throws QuotaExceeded when the provider is out of allowance */
  Generated generate(String prompt, long seed);

  /** False for the placeholder: not worth replacing the initials on avatars with. */
  boolean real();
}
