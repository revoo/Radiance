package app.radiance.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.radiance.content.Vocab.Category;
import app.radiance.content.Vocab.Gender;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ContentLibraryTests {

  static final List<ContentProperties.BlacklistEntry> BLACKLIST = List.of(
      new ContentProperties.BlacklistEntry("pregnancy",
          List.of("pregnant", "pregnancy", "expecting a baby", "baby bump", "trimester", "baby shower",
              "gender reveal")));

  static Map<String, String> shipped() {
    return ContentConfig.classpathFiles("content/");
  }

  static ContentLibrary load(Map<String, String> files) {
    return ContentLoader.load(files, BLACKLIST);
  }

  /** The shipped files with one file replaced or added. */
  static Map<String, String> with(String path, String text) {
    var files = new HashMap<>(shipped());
    files.put(path, text);
    return files;
  }

  static Map<String, String> edit(String path, String from, String to) {
    var files = new HashMap<>(shipped());
    String old = files.get(path);
    assertThat(old).as(path).contains(from);
    files.put(path, old.replace(from, to));
    return files;
  }

  @Test
  void theStarterLibraryLoadsAndMeetsTheSpecsCounts() {
    var lib = load(shipped());
    assertThat(lib.quirks()).hasSizeGreaterThanOrEqualTo(25);
    assertThat(lib.events()).hasSizeGreaterThanOrEqualTo(40);
    var byCategory = lib.events().values().stream()
        .collect(Collectors.groupingBy(Defs.EventDef::category, Collectors.counting()));
    for (var c : Category.values()) {
      assertThat(byCategory.getOrDefault(c, 0L)).as("events in " + c).isGreaterThanOrEqualTo(3);
    }
    assertThat(lib.arcs().keySet()).containsAll(List.of("romance", "breakup_aftermath", "job_search",
        "job_loss", "home_project", "car_saga", "training_goal", "injury_recovery", "feud",
        "side_hustle", "moving", "new_pet", "new_hobby", "school", "affair", "grief"));
    assertThat(lib.constraints().keySet()).containsExactlyInAnyOrder("no_alcohol", "no_travel",
        "no_long_travel", "budget_tight", "homebound", "low_energy", "sleep_deprived", "grieving",
        "injured", "secret", "in_relationship", "single", "avoids", "busy_weekends", "new_job",
        "unemployed");
    assertThat(lib.weather().keySet()).containsExactlyInAnyOrder("snow_day", "first_warm_day",
        "brutal_cold", "heat_wave", "thunderstorm", "washout", "perfect_day");
    assertThat(lib.temperament().presets()).hasSize(3);
    assertThat(lib.pacing().buckets().get("comments").perMinute()).isEqualTo(6);
  }

  @Test
  void theRosterMatchesTheSpec() {
    var roster = load(shipped()).roster();
    assertThat(roster.residents()).hasSize(30);
    assertThat(roster.residents().stream().filter(r -> r.gender() == Gender.F)).hasSize(15);
    assertThat(roster.residents()).extracting(Roster.Resident::resolvedHandle)
        .contains("sarah.brennan", "amra.hodzic", "marko.jovanovic");
    assertThat(roster.residents().stream().filter(r -> r.faith().observance() == Vocab.Observance.DEVOUT))
        .extracting(Roster.Resident::name)
        .containsExactlyInAnyOrder("Brian Whitlock", "Amra Hodžić", "Nick Grabowski", "Eleni Galanis");
    assertThat(roster.pinned()).extracting(Roster.Pinned::name).containsExactly("Sarah Brennan",
        "Tim Kaczmarek", "Jesse Hensley", "Mike Rinaldi", "Jake Zielinski", "Jessica Vogel",
        "Samantha Dvorak");
    assertThat(roster.you().age()).isEqualTo(31);
  }

  @Test
  void aPinnedSeedThatNamesABlacklistedArcFailsWithAClearMessage() {
    var files = edit("cast/roster.yaml", "seeds: { arcs: [\"romance:secret_dating\"] }",
        "seeds: { arcs: [\"pregnancy:announced\"] }");
    assertThatThrownBy(() -> load(files))
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("cast/roster.yaml pinned Jessica Vogel: seeds blacklisted arc 'pregnancy'");
  }

  @Test
  void anEventThatStartsABlacklistedArcFails() {
    var files = with("events/zz_bad.yaml", """
        id: baby_news
        category: LIFE
        base_rate: 0.01
        importance: 5
        output: { post: STATUS, brief: "big news" }
        effects: { start_arc: pregnancy }
        """);
    assertThatThrownBy(() -> load(files))
        .hasMessageContaining("event baby_news: start_arc names unknown arc 'pregnancy'")
        .hasMessageContaining("event baby_news: start_arc names blacklisted arc 'pregnancy'");
  }

  @Test
  void invalidDefsAreAllReportedWithTheirLocation() {
    var files = with("events/zz_bad.yaml", """
        - id: typo_event
          category: DAILY
          base_rat: 0.1
          importance: 2
          output: { post: STATUS, brief: "x" }
        - id: broken_refs
          category: DAILY
          base_rate: 0.1
          importance: 11
          modifiers: { quirk.ghost: 2.0, charisma: 1.5 }
          requires: { tags: [ jetlagged ] }
          participants: { with: "buddy[affinity>40]" }
          when: { calendar: [ arbor_day ], weather: blizzard }
          output: { post: PHOTO }
        """);
    assertThatThrownBy(() -> load(files))
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("events/zz_bad.yaml: Unrecognized property \"base_rat\"");

    var refsOnly = with("events/zz_bad.yaml", """
        id: broken_refs
        category: DAILY
        base_rate: 0.1
        importance: 11
        modifiers: { quirk.ghost: 2.0, charisma: 1.5 }
        requires: { tags: [ jetlagged ] }
        participants: { with: "buddy[affinity>40]" }
        when: { calendar: [ arbor_day ], weather: blizzard }
        output: { post: PHOTO }
        """);
    assertThatThrownBy(() -> load(refsOnly))
        .satisfies(e -> assertThat(((ContentException) e).problems()).contains(
            "event broken_refs: importance must be 1..10",
            "event broken_refs: posts need a brief",
            "event broken_refs: modifier names unknown quirk 'quirk.ghost'",
            "event broken_refs: unknown modifier 'charisma'",
            "event broken_refs requires: unknown constraint tag 'jetlagged'",
            "event broken_refs: participant 'with': unknown relation 'buddy' in 'buddy[affinity>40]'",
            "event broken_refs: unknown calendar trigger 'arbor_day'",
            "event broken_refs: unknown weather condition 'blizzard'"));
  }

  @Test
  void arcGraphsAreChecked() {
    var files = with("arcs/zz_bad.yaml", """
        id: dead_end
        category: LIFE
        max_silence_days: 5
        roles: { a: {} }
        phases:
          - id: one
            duration_days: [5, 1]
            knowers: [ a, closest_friend_of:z ]
            transitions: [ { to: two, p: 0.5 }, { to: nowhere, p: 0.3 } ]
          - id: two
            duration_days: [1, 2]
            next: one
          - id: done
            terminal: true
        """);
    assertThatThrownBy(() -> load(files))
        .satisfies(e -> assertThat(((ContentException) e).problems()).contains(
            "arc dead_end phase one: duration_days must be [min, max]",
            "arc dead_end phase one: transition to unknown phase 'nowhere'",
            "arc dead_end phase one: transition probabilities sum to 0.8, not 1",
            "arc dead_end phase one: knower names unknown role 'closest_friend_of:z'",
            "arc dead_end phase done: terminal phases need an entry_event (no silent endings)",
            "arc dead_end: phase one can't reach an ending"));
  }

  @Test
  void rosterRulesAreChecked() {
    var files = edit("cast/roster.yaml", "status: { kind: DATING, with: Sarah Brennan }",
        "status: { kind: DATING, with: Megan Quinlan }");
    assertThatThrownBy(() -> load(files))
        .satisfies(e -> assertThat(((ContentException) e).problems()).contains(
            "cast/roster.yaml Ryan Kessler: partner 'Megan Quinlan' doesn't list them back",
            "cast/roster.yaml Sarah Brennan: partner 'Ryan Kessler' doesn't list them back"));

    var files2 = new HashMap<>(shipped());
    files2.put("cast/roster.yaml", files2.get("cast/roster.yaml")
        .replace("status: { kind: SINGLE }              # secretly seeing someone (pinned below)",
            "status: { kind: DATING, with: Megan Quinlan }")
        .replace("work: { title: ICU nurse, employer: Prairie Path Medical Center }\n    status: { kind: SINGLE }",
            "work: { title: ICU nurse, employer: Prairie Path Medical Center }\n    status: { kind: DATING, with: Jessica Vogel }"));
    assertThatThrownBy(() -> load(files2))
        .hasMessageContaining("cast/roster.yaml Jessica Vogel: every couple pairs a man and a woman (invariant 11)");

    var tooYoung = edit("cast/roster.yaml", "age: 24", "age: 19");
    assertThatThrownBy(() -> load(tooYoung))
        .hasMessageContaining("cast/roster.yaml Hannah Lindqvist: age must be 22..70, is 19");
  }

  @Test
  void theThemeLinterMatchesWholeWordsAndPhrases() {
    var linter = new ThemeLinter(BLACKLIST);
    assertThat(linter.hits("Welcome to the world, Nora!")).isEmpty();
    assertThat(linter.hits("Gender  Reveal party this weekend")).extracting(ThemeLinter.Hit::term)
        .containsExactly("gender reveal");
    assertThat(linter.hits("she's PREGNANT!!")).extracting(ThemeLinter.Hit::arcId)
        .containsExactly("pregnancy");
    assertThat(linter.hits("unpregnantlike")).isEmpty();
  }

  @Test
  void selectorsParse() {
    var s = Selector.parse("circle:softball[familiarity>=20, affinity>-5]");
    assertThat(s.relation()).isEqualTo("circle");
    assertThat(s.param()).isEqualTo("softball");
    assertThat(s.conditions()).containsExactly(
        new Selector.Condition("familiarity", ">=", 20), new Selector.Condition("affinity", ">", -5));
    assertThat(Arrays.asList("anyone", "user[attraction>40]"))
        .allSatisfy(t -> assertThat(Selector.parse(t)).isNotNull());
  }
}
