package app.radiance.world;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.content.Vocab;
import java.time.LocalDate;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class CalendarTests {

  @Test
  void knownDates() {
    assertThat(Calendar.easter(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
    assertThat(Calendar.easter(2027)).isEqualTo(LocalDate.of(2027, 3, 28));
    assertThat(Calendar.orthodoxEaster(2026)).isEqualTo(LocalDate.of(2026, 4, 12));
    assertThat(Calendar.orthodoxEaster(2027)).isEqualTo(LocalDate.of(2027, 5, 2));
    assertThat(Calendar.triggers(LocalDate.of(2026, 11, 26))).contains("thanksgiving");
    assertThat(Calendar.triggers(LocalDate.of(2026, 11, 3))).contains("election_day");
    assertThat(Calendar.triggers(LocalDate.of(2027, 11, 2))).doesNotContain("election_day");
    assertThat(Calendar.triggers(LocalDate.of(2026, 10, 31))).containsExactly("halloween");
    assertThat(Calendar.triggers(LocalDate.of(2027, 2, 14))).contains("super_bowl_sunday", "valentines_day");
    assertThat(Calendar.triggers(LocalDate.of(2027, 3, 13))).contains("st_patricks_parade");
    assertThat(Calendar.triggers(LocalDate.of(2027, 3, 12))).contains("lent_friday");
    assertThat(Calendar.triggers(LocalDate.of(2026, 11, 8))).contains("diwali");
  }

  @Test
  void everyTriggerTheCalendarEmitsIsInTheVocabularyAndMostOccurEachYear() {
    var seen = new TreeSet<String>();
    for (var d = LocalDate.of(2027, 1, 1); d.getYear() == 2027; d = d.plusDays(1)) seen.addAll(Calendar.triggers(d));
    assertThat(Vocab.CALENDAR).containsAll(seen);
    var missing = new TreeSet<>(Vocab.CALENDAR);
    missing.removeAll(seen);
    // birthdays are per resident; election day falls in even years
    assertThat(missing).containsExactlyInAnyOrder("birthday", "election_day");
  }

  @Test
  void leapDayBirthdaysFallOnTheTwentyEighth() {
    assertThat(Calendar.isBirthday("1996-02-29", LocalDate.of(2027, 2, 28))).isTrue();
    assertThat(Calendar.isBirthday("1996-02-29", LocalDate.of(2028, 2, 29))).isTrue();
    assertThat(Calendar.isBirthday("1990-06-01", LocalDate.of(2027, 6, 1))).isTrue();
  }
}
