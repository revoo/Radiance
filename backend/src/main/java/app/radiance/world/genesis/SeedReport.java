package app.radiance.world.genesis;

import java.time.LocalDate;
import java.time.Period;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A readable cast sheet of a seed ({@code world-seed.md}) for R's review (SPEC §6.4, M3 ⏸ REVIEW).
 * Edits go in {@code world-seed.json}; this file is regenerated from it.
 */
public final class SeedReport {

  private SeedReport() {}

  public static String markdown(WorldSeed s) {
    var ref = LocalDate.parse(s.referenceDate());
    Map<String, WorldSeed.Resident> byHandle = s.residents().stream()
        .collect(Collectors.toMap(WorldSeed.Resident::handle, r -> r));
    var sb = new StringBuilder();
    sb.append("# Radiance cast — seed ").append(s.seed()).append(" (").append(s.referenceDate()).append(")\n\n");
    sb.append("Generated from `world-seed.json`. Edit that file, not this one; then import it.\n\n");
    if (s.provenance() != null) sb.append("Prose: ").append(s.provenance()).append(".\n\n");

    sb.append("## Storylines\n\n");
    for (var a : s.arcs()) {
      sb.append("- **").append(a.defId().replace('_', ' ')).append("** — ").append(a.phase().replace('_', ' '))
          .append(" (").append(a.visibility()).append(", ").append(a.daysIntoPhase()).append(" days in): ")
          .append(a.roles().entrySet().stream().map(e -> e.getKey() + " = " + name(byHandle, e.getValue()))
              .collect(Collectors.joining(", ")));
      if (!a.knowers().isEmpty()) {
        sb.append(". Known only to ").append(a.knowers().stream().map(h -> name(byHandle, h))
            .collect(Collectors.joining(" and ")));
      }
      sb.append('\n');
    }

    sb.append("\n## Notable ties\n\n");
    s.relationships().stream()
        .filter(e -> !e.tags().isEmpty() || e.kind().equals("EX") || e.kind().equals("RIVAL") || e.kind().equals("PARTNER"))
        .sorted(Comparator.comparing(WorldSeed.Relationship::from).thenComparing(WorldSeed.Relationship::to))
        .forEach(e -> sb.append("- ").append(name(byHandle, e.from())).append(" → ").append(name(byHandle, e.to()))
            .append(": ").append(e.kind().toLowerCase().replace('_', ' '))
            .append(e.tags().isEmpty() ? "" : " " + e.tags())
            .append(String.format(" (affinity %.0f, attraction %.0f)", e.affinity(), e.attraction()))
            .append(e.backstory() == null ? "" : " — " + e.backstory()).append('\n'));

    sb.append("\n## Residents\n");
    for (var r : s.residents()) {
      int age = Period.between(LocalDate.parse(r.birthDate()), ref).getYears();
      sb.append("\n### ").append(r.name()).append(r.displayName() != null ? " (" + r.displayName() + ")" : "")
          .append(r.pinned() ? " ★" : "").append("\n\n");
      sb.append(age).append(", ").append(r.occupation()).append(r.employer() != null ? " at " + r.employer() : "")
          .append(", ").append(r.livesIn()).append(". ").append(r.status().name().toLowerCase())
          .append(r.partner() != null ? " (with " + name(byHandle, r.partner()) + ")" : "").append(". ")
          .append(r.heritage()).append("; ").append(r.faithTradition() == null ? "no faith" : r.faithTradition())
          .append(" (").append(r.observance().name().toLowerCase()).append("). Circles: ")
          .append(String.join(", ", r.circles())).append(".\n\n");
      if (r.archetype() != null) sb.append("*Pinned:* ").append(r.archetype()).append("\n\n");
      var t = r.traits();
      sb.append("- **Temperament** ").append(r.temperament().preset()).append(" · **quirks** ")
          .append(String.join(", ", r.quirks())).append('\n');
      sb.append("- **Big Five** O").append(t.get("openness")).append(" C").append(t.get("conscientiousness"))
          .append(" E").append(t.get("extraversion")).append(" A").append(t.get("agreeableness"))
          .append(" N").append(t.get("neuroticism")).append(" · argumentative ").append(t.get("argumentativeness"))
          .append(" · humor ").append(t.get("humor")).append(" · flirtatious ").append(t.get("flirtatiousness"))
          .append(" · faithfulness ").append(t.get("faithfulness")).append('\n');
      sb.append(String.format("- **Politics** economic %+.2f, social %+.2f, interest %.2f%n",
          r.politics().economic(), r.politics().social(), r.politics().interest()));
      sb.append("- **Biases** ").append(r.biases().stream()
          .map(b -> b.target() + (b.strong() ? " (STRONG, " + b.style().toLowerCase() + ")" : ""))
          .collect(Collectors.joining(", "))).append('\n');
      sb.append("- **Routine** ").append(r.routine().schedule()).append(", ").append(r.routine().chronotype())
          .append(String.format(", %.2f posts/day", r.routine().postsPerDay())).append('\n');
      if (!r.offNetwork().isEmpty()) {
        sb.append("- **Off-network** ").append(r.offNetwork().stream()
            .map(p -> p.name() + " (" + p.relation() + (p.age() != null ? ", " + p.age() : "") + ")")
            .collect(Collectors.joining(", "))).append('\n');
      }
      s.entities().stream().filter(e -> e.owner().equals(r.handle())).forEach(e -> sb.append("- **")
          .append(e.kind().name().toLowerCase()).append("** ").append(e.name()).append(": ")
          .append(e.visualDescription()).append('\n'));
      if (r.bio() == null) {
        sb.append("\n*Not fleshed out yet.*\n");
      } else {
        sb.append("\n> ").append(r.bio()).append("\n\n").append(r.backstory()).append("\n\n");
        if (r.appearance() != null) sb.append("*Looks:* ").append(r.appearance()).append("\n\n");
        sb.append("Values: ").append(String.join("; ", r.values())).append(". Fears: ")
            .append(String.join("; ", r.fears())).append(". Wants: ").append(String.join("; ", r.desires()))
            .append(".\n\n");
        var v = r.voice();
        sb.append("Voice: ").append(v.lengthBand().toLowerCase()).append(", emoji ")
            .append(v.emojiLevel().toLowerCase()).append(", ").append(v.casing().toLowerCase())
            .append(", ").append(v.punctuation()).append(". Catchphrases: ")
            .append(String.join(" / ", v.catchphrases())).append("\n\n");
        v.samplePosts().forEach(p -> sb.append("- post: ").append(p).append('\n'));
        v.sampleComments().forEach(c -> sb.append("- comment: ").append(c).append('\n'));
      }
    }
    return sb.toString();
  }

  private static String name(Map<String, WorldSeed.Resident> byHandle, String handle) {
    var r = byHandle.get(handle);
    return r == null ? handle : r.displayName() != null ? r.displayName() : r.name();
  }
}
