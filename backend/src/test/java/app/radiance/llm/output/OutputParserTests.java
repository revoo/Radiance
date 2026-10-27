package app.radiance.llm.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class OutputParserTests {

  enum Stance { SUPPORT, TROLL, MOCK }

  record Fact(String subject, String statement, boolean major) {}

  record Item(String briefId, String text, Stance stance, List<Fact> introducedFacts) {}

  record Batch(List<Item> items, int count, double score, List<Stance> tags) {}

  static final String GOOD = """
      {"items":[{"briefId":"c1","text":"lol ok","stance":"TROLL",
        "introducedFacts":[{"subject":"@sam","statement":"bought a tile saw","major":false}]}],
       "count":1,"score":0.5,"tags":["MOCK"]}""";

  @Test
  void schemaCoversTheSupportedSubset() {
    var schema = OutputSchema.of(Batch.class);
    assertThat(schema.get("required").toString()).isEqualTo("[\"items\",\"count\",\"score\",\"tags\"]");
    assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
    var item = schema.at("/properties/items/items");
    assertThat(item.at("/properties/stance/enum").toString())
        .isEqualTo("[\"SUPPORT\",\"TROLL\",\"MOCK\"]");
    assertThat(item.at("/properties/introducedFacts/items/properties/major/type").asString())
        .isEqualTo("boolean");
    assertThat(schema.at("/properties/count/type").asString()).isEqualTo("integer");
    assertThat(schema.at("/properties/score/type").asString()).isEqualTo("number");
    assertThat(schema.toString()).doesNotContain("minimum", "maxLength", "format");
  }

  record WithReasoning(String reasoning, String answer) {}

  @Test
  void refusesReasoningFields() {
    assertThatThrownBy(() -> OutputSchema.of(WithReasoning.class))
        .hasMessageContaining("no reasoning fields");
  }

  @Test
  void parsesCleanJson() throws Exception {
    var b = OutputParser.parse(GOOD, Batch.class);
    assertThat(b.items()).hasSize(1);
    assertThat(b.items().getFirst().stance()).isEqualTo(Stance.TROLL);
    assertThat(b.items().getFirst().introducedFacts().getFirst().statement())
        .isEqualTo("bought a tile saw");
  }

  @Test
  void stripsFencesAndSurroundingProse() throws Exception {
    assertThat(OutputParser.parse("Sure! Here you go:\n```json\n" + GOOD + "\n```\nHope that helps",
        Batch.class).count()).isEqualTo(1);
    assertThat(OutputParser.parse("Here it is: " + GOOD + " -- done", Batch.class).count())
        .isEqualTo(1);
  }

  @Test
  void normalizesEnumCasing() throws Exception {
    var b = OutputParser.parse(GOOD.replace("\"TROLL\"", "\"troll\"").replace("\"MOCK\"", "\" Mock\""),
        Batch.class);
    assertThat(b.items().getFirst().stance()).isEqualTo(Stance.TROLL);
    assertThat(b.tags()).containsExactly(Stance.MOCK);
  }

  @Test
  void ignoresExtraFieldsAndAcceptsWholeFloatsAsIntegers() throws Exception {
    var b = OutputParser.parse(GOOD.replace("\"count\":1", "\"count\":1.0,\"extra\":\"x\""),
        Batch.class);
    assertThat(b.count()).isEqualTo(1);
  }

  @Test
  void reportsEveryProblemWithAPath() {
    var bad = GOOD.replace("\"stance\":\"TROLL\"", "\"stance\":\"HUG\"")
        .replace("\"count\":1,", "")
        .replace("\"score\":0.5", "\"score\":\"high\"")
        .replace("\"major\":false", "\"major\":\"no\"");
    assertThatThrownBy(() -> OutputParser.parse(bad, Batch.class))
        .isInstanceOf(InvalidOutputException.class)
        .satisfies(e -> assertThat(((InvalidOutputException) e).errors()).containsExactlyInAnyOrder(
            "$.items[0].stance: \"HUG\" is not one of [\"SUPPORT\",\"TROLL\",\"MOCK\"]",
            "$.items[0].introducedFacts[0].major: expected true or false",
            "$.count: missing",
            "$.score: expected a number"));
  }

  @Test
  void rejectsNonJson() {
    assertThatThrownBy(() -> OutputParser.parse("I can't help with that.", Batch.class))
        .hasMessageContaining("no JSON object");
    assertThatThrownBy(() -> OutputParser.parse("", Batch.class)).hasMessageContaining("empty");
  }
}
