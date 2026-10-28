package app.radiance.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class HeadlineTests {

  static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
  static final Map<String, Double> MIX = Map.of("NATIONAL", 0.6, "WORLD", 0.3, "LOCAL", 0.1);

  @Test
  void fixturesAreSeededPerDay() {
    var f = new FixtureHeadlines(7, CHICAGO, 100);
    var day = LocalDate.of(2026, 10, 28);
    assertThat(f.on(day)).isEqualTo(new FixtureHeadlines(7, CHICAGO, 100).on(day));
    assertThat(f.on(day)).isNotEqualTo(f.on(day.plusDays(1)));
    assertThat(f.on(day)).allSatisfy(h -> {
      assertThat(h.publishedAt()).isBetween(day.atStartOfDay(CHICAGO).toInstant().toEpochMilli(),
          day.plusDays(1).atStartOfDay(CHICAGO).toInstant().toEpochMilli());
      assertThat(h.classifiedAt()).isNotNull();
    });
  }

  @Test
  void selectionFollowsTheRegionMixAndSalience() {
    var all = new FixtureHeadlines(7, CHICAGO, 100).on(LocalDate.of(2026, 10, 28));
    var picked = HeadlineSelection.select(all, 25, MIX);
    assertThat(picked).hasSize(25);
    var byRegion = picked.stream().collect(Collectors.groupingBy(Headline::region, Collectors.counting()));
    assertThat(byRegion).containsEntry("NATIONAL", 15L).containsEntry("WORLD", 8L).containsEntry("LOCAL", 2L);
    // a short region gives its slots away
    var noLocal = all.stream().filter(h -> !h.region().equals("LOCAL")).toList();
    assertThat(HeadlineSelection.select(noLocal, 25, MIX)).hasSize(25);
  }
}
