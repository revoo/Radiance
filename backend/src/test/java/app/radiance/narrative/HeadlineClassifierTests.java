package app.radiance.narrative;

import static org.assertj.core.api.Assertions.assertThat;

import app.radiance.narrative.HeadlineClassifier.Batch;
import app.radiance.narrative.HeadlineClassifier.Entry;
import app.radiance.news.Headline;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Model output is checked here, whatever the provider promises (CLAUDE.md). */
class HeadlineClassifierTests {

  static Headline row(String id) {
    return new Headline(id, "https://e.com/" + id, "Wire", "Title " + id, null, 1, 1, null, null, 0, 0, null, null, null);
  }

  static Entry entry(int n, String region) {
    return new Entry(n, region, List.of("politics"), 0.5, 0.5, "NORMAL", 0.5, -0.5, 1);
  }

  @Test
  void keepsAGoodAnswerAndScalesTheAxesByConfidence() {
    var e = new Entry(1, "LOCAL", List.of("local", "sports:bears"), 0.7, 0.9, "TRAGIC", 0.8, -0.4, 0.5);
    var h = HeadlineClassifier.apply(List.of(row("a")), new Batch(List.of(e)), 99).getFirst();
    assertThat(h.id()).isEqualTo("a");
    assertThat(h.region()).isEqualTo("LOCAL");
    assertThat(h.topics()).containsExactly("local", "sports:bears");
    assertThat(h.controversy()).isEqualTo(0.7);
    assertThat(h.salience()).isEqualTo(0.9);
    assertThat(h.tragic()).isTrue();
    assertThat(h.axisSignal()).containsEntry("economic", 0.4).containsEntry("social", -0.2);
    assertThat(h.classifiedAt()).isEqualTo(99);
  }

  @Test
  void dropsEntriesItCantPlaceSoThoseHeadlinesTryAgainLater() {
    var rows = List.of(row("a"), row("b"));
    var answer = new Batch(Arrays.asList(entry(0, "LOCAL"), entry(3, "LOCAL"), entry(1, "MARS"), null, entry(2, "WORLD"),
        entry(2, "LOCAL")));
    var out = HeadlineClassifier.apply(rows, answer, 1);
    assertThat(out).extracting(Headline::id).containsExactly("b");
    assertThat(out.getFirst().region()).as("the first answer for a number wins").isEqualTo("WORLD");
  }

  @Test
  void clampsNumbersAndFiltersWordsToWhatWeKnow() {
    var e = new Entry(1, "WORLD", Arrays.asList("politics", "astrology", "politics", "tech", "food", "cars"), 3, -2, "SPICY", 9,
        -9, 7);
    var h = HeadlineClassifier.apply(List.of(row("a")), new Batch(List.of(e)), 1).getFirst();
    assertThat(h.topics()).containsExactly("politics", "tech", "food");
    assertThat(h.controversy()).isEqualTo(1);
    assertThat(h.salience()).isEqualTo(0);
    assertThat(h.sensitivity()).isEqualTo("NORMAL");
    assertThat(h.axisSignal()).containsEntry("economic", 1.0).containsEntry("social", -1.0);
  }

  @Test
  void toleratesFieldsAModelLeftOut() {
    var e = new Entry(1, "WORLD", null, 0, 0, null, 0, 0, 0);
    var h = HeadlineClassifier.apply(List.of(row("a")), new Batch(List.of(e)), 1).getFirst();
    assertThat(h.topics()).isEmpty();
    assertThat(h.sensitivity()).isEqualTo("NORMAL");
    var noRegion = new Entry(1, null, Arrays.asList("tech", null), 0, 0, "NORMAL", 0, 0, 0);
    assertThat(HeadlineClassifier.apply(List.of(row("a")), new Batch(List.of(noRegion)), 1)).isEmpty();
  }

  @Test
  void theFakeAnswersEveryLineAndAlwaysTheSame() {
    String text = "<headlines>\n1. [W] Chicago council passes a budget\n2. [W] Two killed in a crash\n</headlines>";
    var a = HeadlineClassifier.fake(text);
    assertThat(a.items()).hasSize(2);
    assertThat(a.items().getFirst().region()).isEqualTo("LOCAL");
    assertThat(a.items().get(1).sensitivity()).isEqualTo("TRAGIC");
    assertThat(HeadlineClassifier.fake(text)).isEqualTo(a);
  }
}
