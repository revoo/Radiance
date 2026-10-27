package app.radiance.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.stream.LongStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class RandomSourceTests {

  /**
   * Pinned outputs. If this fails after a code or JDK change, every seeded world changes: that is a
   * breaking change to determinism (SPEC P6), not a test to update casually.
   */
  @Test
  void goldenValuesAreStable() {
    var rng = RandomSource.seeded(42);
    assertThat(LongStream.generate(rng::nextLong).limit(3).toArray()).containsExactly(GOLDEN_42);
    assertThat(RandomSource.forSlice(42, 1_790_000_000_000L).nextLong()).isEqualTo(GOLDEN_SLICE);
    assertThat(RandomSource.seeded(42).derive("genesis").nextLong()).isEqualTo(GOLDEN_DERIVED);
  }

  static final long[] GOLDEN_42 = {1546998764402558742L, 6990951692964543102L, -5902157311460992607L};
  static final long GOLDEN_SLICE = -5411094117380714721L;
  static final long GOLDEN_DERIVED = 4963539070768233969L;

  /** xoshiro256** reference vector: state {1, 2, 3, 4}. */
  @Test
  void matchesTheReferenceGenerator() throws ReflectiveOperationException {
    var rng = RandomSource.seeded(0);
    String[] fields = {"s0", "s1", "s2", "s3"};
    for (int i = 0; i < 4; i++) {
      var f = RandomSource.class.getDeclaredField(fields[i]);
      f.setAccessible(true);
      f.setLong(rng, i + 1);
    }
    assertThat(LongStream.generate(rng::nextLong).limit(4).toArray())
        .containsExactly(11520L, 0L, 1509978240L, 1215971899390074240L);
  }

  @Property
  void sameSeedSameSequence(@ForAll long seed) {
    var a = RandomSource.seeded(seed);
    var b = RandomSource.seeded(seed);
    for (int i = 0; i < 20; i++) assertThat(a.nextLong()).isEqualTo(b.nextLong());
  }

  @Test
  void derivedStreamsDoNotDependOnParentDraws() {
    var fresh = RandomSource.seeded(9);
    var used = RandomSource.seeded(9);
    for (int i = 0; i < 1000; i++) used.nextLong();

    assertThat(used.derive("weather").nextLong()).isEqualTo(fresh.derive("weather").nextLong());
    assertThat(fresh.derive("weather").nextLong()).isNotEqualTo(fresh.derive("news").nextLong());
  }

  @Test
  void slicesAndActorsGetDistinctStreams() {
    long t = 1_790_000_000_000L;
    assertThat(RandomSource.forSlice(1, t).nextLong())
        .isNotEqualTo(RandomSource.forSlice(1, t + 3_600_000).nextLong())
        .isNotEqualTo(RandomSource.forSlice(2, t).nextLong());
    assertThat(RandomSource.forActor(1, "a", t).nextLong())
        .isNotEqualTo(RandomSource.forActor(1, "b", t).nextLong());
  }

  @Property
  void nextIntStaysInBounds(@ForAll long seed, @ForAll @IntRange(min = 1) int bound) {
    var rng = RandomSource.seeded(seed);
    for (int i = 0; i < 50; i++) assertThat(rng.nextInt(bound)).isBetween(0, bound - 1);
  }

  @Test
  void distributionsLookRight() {
    var rng = RandomSource.seeded(123);
    int n = 200_000;
    int[] buckets = new int[10];
    double sum = 0;
    double sumSq = 0;
    double uniformSum = 0;
    for (int i = 0; i < n; i++) {
      buckets[rng.nextInt(10)]++;
      double g = rng.nextGaussian();
      sum += g;
      sumSq += g * g;
      uniformSum += rng.nextDouble();
    }
    for (int b : buckets) assertThat(b).isCloseTo(n / 10, within(n / 100));
    assertThat(sum / n).isCloseTo(0.0, within(0.01));
    assertThat(Math.sqrt(sumSq / n)).isCloseTo(1.0, within(0.01));
    assertThat(uniformSum / n).isCloseTo(0.5, within(0.005));
  }
}
