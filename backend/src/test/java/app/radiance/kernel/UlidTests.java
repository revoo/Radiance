package app.radiance.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class UlidTests {

  @Property
  void roundTripsThroughItsString(
      @ForAll @LongRange(min = 0, max = (1L << 48) - 1) long time, @ForAll long seed) {
    var id = Ulid.of(time, RandomSource.seeded(seed));
    var text = id.toString();

    assertThat(text).hasSize(26).matches("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    assertThat(Ulid.parse(text)).isEqualTo(id);
    assertThat(Ulid.parse(text.toLowerCase())).isEqualTo(id);
    assertThat(id.timestampMs()).isEqualTo(time);
  }

  @Property
  void stringOrderMatchesTimeOrder(
      @ForAll @LongRange(min = 0, max = (1L << 48) - 2) long t, @ForAll long seed) {
    var rng = RandomSource.seeded(seed);
    var earlier = Ulid.of(t, rng);
    var later = Ulid.of(t + 1, rng);

    assertThat(earlier.toString()).isLessThan(later.toString());
    assertThat(earlier).isLessThan(later);
  }

  @Test
  void sameSeedAndTimeGiveTheSameIds() {
    var a = RandomSource.seeded(7);
    var b = RandomSource.seeded(7);
    for (int i = 0; i < 100; i++) {
      assertThat(Ulid.next(1_790_000_000_000L + i, a))
          .isEqualTo(Ulid.next(1_790_000_000_000L + i, b));
    }
  }

  @Test
  void encodesKnownValues() {
    assertThat(new Ulid(0, 0).toString()).isEqualTo("00000000000000000000000000");
    assertThat(new Ulid(-1, -1).toString()).isEqualTo("7ZZZZZZZZZZZZZZZZZZZZZZZZZ");
    assertThat(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV").timestampMs()).isEqualTo(1469922850259L);
  }

  @Test
  void rejectsMalformedInput() {
    assertThatThrownBy(() -> Ulid.parse("8ZZZZZZZZZZZZZZZZZZZZZZZZZ"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAU"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Ulid.parse("short")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Ulid.of(-1, RandomSource.seeded(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
