package app.radiance.kernel;

/**
 * A ULID: 48 bits of epoch milliseconds then 80 random bits, written as 26 Crockford base32 characters.
 * String order equals time order.
 *
 * <p>IDs must be reproducible (SPEC §4.2, P6): the same seed and inputs produce the same script, and
 * a retried unit writes the same rows. So there is no hidden clock or {@code SecureRandom} here — the
 * caller supplies the time (for scripted items, their planned air time) and a seeded
 * {@link RandomSource} (ADR 0011). Because air times can move after an ID is minted, API cursors use
 * {@code (created_at, id)}, never the ID alone.
 */
public record Ulid(long msb, long lsb) implements Comparable<Ulid> {

  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final long MAX_TIME = (1L << 48) - 1;
  private static final int[] DECODE = new int[128];

  static {
    java.util.Arrays.fill(DECODE, -1);
    for (int i = 0; i < ALPHABET.length; i++) {
      DECODE[ALPHABET[i]] = i;
      DECODE[Character.toLowerCase(ALPHABET[i])] = i;
    }
  }

  public static Ulid of(long timeMs, RandomSource rng) {
    if (timeMs < 0 || timeMs > MAX_TIME) {
      throw new IllegalArgumentException("ULID time out of range: " + timeMs);
    }
    long hi16 = rng.nextLong() & 0xFFFFL;
    long lo64 = rng.nextLong();
    return new Ulid((timeMs << 16) | hi16, lo64);
  }

  /** {@link #of} as a string, the form stored in every {@code id TEXT} column. */
  public static String next(long timeMs, RandomSource rng) {
    return of(timeMs, rng).toString();
  }

  public static Ulid parse(String s) {
    if (s == null || s.length() != 26) {
      throw new IllegalArgumentException("not a ULID: " + s);
    }
    long msb = 0;
    long lsb = 0;
    for (int i = 0; i < 26; i++) {
      char c = s.charAt(i);
      int d = c < 128 ? DECODE[c] : -1;
      if (d < 0 || (i == 0 && d > 7)) {
        throw new IllegalArgumentException("not a ULID: " + s);
      }
      msb = (msb << 5) | (lsb >>> 59);
      lsb = (lsb << 5) | d;
    }
    return new Ulid(msb, lsb);
  }

  public long timestampMs() {
    return msb >>> 16;
  }

  @Override
  public String toString() {
    char[] out = new char[26];
    long hi = msb;
    long lo = lsb;
    for (int i = 25; i >= 0; i--) {
      out[i] = ALPHABET[(int) (lo & 31)];
      lo = (lo >>> 5) | (hi << 59);
      hi >>>= 5;
    }
    return new String(out);
  }

  @Override
  public int compareTo(Ulid o) {
    int c = Long.compareUnsigned(msb, o.msb);
    return c != 0 ? c : Long.compareUnsigned(lsb, o.lsb);
  }
}
