package app.radiance.kernel;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Seeded randomness (SPEC P6). Same seed ⇒ same sequence, on every JDK: the generator (xoshiro256**),
 * the seed derivation (SplitMix64) and every distribution here are implemented in-house rather than
 * borrowed from {@code java.util.random}, whose default methods are free to change.
 *
 * <p>Streams are derived, not forked: {@link #derive(String)} and the static factories hash the
 * original seed with a label, so a child stream doesn't depend on how many values the parent drew.
 *
 * <p>Not thread-safe; give each thread or planning unit its own stream.
 */
public final class RandomSource {

  private static final long GOLDEN = 0x9E3779B97F4A7C15L;

  private final long seed;
  private long s0;
  private long s1;
  private long s2;
  private long s3;

  private RandomSource(long seed) {
    this.seed = seed;
    long x = seed;
    s0 = mix(x += GOLDEN);
    s1 = mix(x += GOLDEN);
    s2 = mix(x += GOLDEN);
    s3 = mix(x + GOLDEN);
  }

  public static RandomSource seeded(long seed) {
    return new RandomSource(seed);
  }

  /** The stream for one planning slice: {@code RandomSource.forSlice(world.seed, slice.start)}. */
  public static RandomSource forSlice(long worldSeed, long sliceStartMs) {
    return new RandomSource(combine(combine(worldSeed, labelHash("slice")), sliceStartMs));
  }

  /** The stream for one actor within a slice, independent of every other actor's draws. */
  public static RandomSource forActor(long worldSeed, String actorId, long sliceStartMs) {
    return new RandomSource(
        combine(combine(combine(worldSeed, labelHash("actor")), labelHash(actorId)), sliceStartMs));
  }

  /** An independent child stream named by {@code label}; unaffected by draws on this stream. */
  public RandomSource derive(String label) {
    return new RandomSource(combine(seed, labelHash(label)));
  }

  /** An independent child stream keyed by a number (a slice start, an index). */
  public RandomSource derive(long key) {
    return new RandomSource(combine(seed, key));
  }

  public long seed() {
    return seed;
  }

  /** xoshiro256** (Blackman & Vigna). */
  public long nextLong() {
    long result = Long.rotateLeft(s1 * 5, 7) * 9;
    long t = s1 << 17;
    s2 ^= s0;
    s3 ^= s1;
    s1 ^= s2;
    s0 ^= s3;
    s2 ^= t;
    s3 = Long.rotateLeft(s3, 45);
    return result;
  }

  /** Uniform in [0, bound), unbiased (Lemire's method). */
  public int nextInt(int bound) {
    if (bound <= 0) throw new IllegalArgumentException("bound must be positive: " + bound);
    long m = (nextLong() >>> 32) * bound;
    long low = m & 0xFFFFFFFFL;
    if (low < bound) {
      long threshold = (0x1_0000_0000L - bound) % bound;
      while (low < threshold) {
        m = (nextLong() >>> 32) * bound;
        low = m & 0xFFFFFFFFL;
      }
    }
    return (int) (m >>> 32);
  }

  /** Uniform in [origin, bound). */
  public int nextInt(int origin, int bound) {
    if (origin >= bound) throw new IllegalArgumentException("origin must be < bound");
    return origin + nextInt(bound - origin);
  }

  /** Uniform in [0, 1) with 53 bits of precision. */
  public double nextDouble() {
    return (nextLong() >>> 11) * 0x1.0p-53;
  }

  /** Uniform in [origin, bound). */
  public double nextDouble(double origin, double bound) {
    return origin + (bound - origin) * nextDouble();
  }

  /** True with probability {@code p}. Always draws exactly one value. */
  public boolean chance(double p) {
    return nextDouble() < p;
  }

  /** Standard normal via Box–Muller. Always draws exactly two values; nothing is cached. */
  public double nextGaussian() {
    double u1 = 1.0 - nextDouble(); // (0, 1]
    double u2 = nextDouble();
    return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
  }

  public double nextGaussian(double mean, double sd) {
    return mean + sd * nextGaussian();
  }

  public <T> T pick(List<T> items) {
    if (items.isEmpty()) throw new IllegalArgumentException("cannot pick from an empty list");
    return items.get(nextInt(items.size()));
  }

  /** SplitMix64's finalizer: a strong 64-bit mix. */
  static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  static long combine(long h, long v) {
    return mix(h ^ mix(v + GOLDEN));
  }

  /** FNV-1a over UTF-8, then mixed: stable across JDKs, unlike {@code String.hashCode} widening. */
  static long labelHash(String label) {
    long h = 0xCBF29CE484222325L;
    for (byte b : label.getBytes(StandardCharsets.UTF_8)) {
      h ^= (b & 0xFF);
      h *= 0x100000001B3L;
    }
    return mix(h);
  }
}
