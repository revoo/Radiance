package app.radiance.content;

import app.radiance.content.Vocab.Gender;
import app.radiance.content.Vocab.Observance;
import app.radiance.content.Vocab.StatusKind;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** {@code content/cast/roster.yaml}: the fixed V1 cast (SPEC §6.2, §6.3). */
public record Roster(List<Resident> residents, You you, List<Pinned> pinned) {

  public Roster {
    residents = Defs.list(residents);
    pinned = Defs.list(pinned);
  }

  /**
   * @param notes household details that aren't residents ("one toddler", "two kids every other
   *     weekend"); genesis writes them up as off-network people
   */
  public record Resident(
      String name,
      String displayName,
      String handle,
      Gender gender,
      int age,
      String heritage,
      Faith faith,
      String livesIn,
      Work work,
      Status status,
      List<String> circles,
      List<FamilyLink> family,
      List<Knows> knows,
      String notes) {

    public Resident {
      circles = Defs.list(circles);
      family = Defs.list(family);
      knows = Defs.list(knows);
    }

    /** The display name, or the full name when the entry sets none. */
    public String shownName() {
      return displayName == null ? name : displayName;
    }

    /** The entry's handle, or one derived from the name: lowercase ASCII, {@code first.last}. */
    public String resolvedHandle() {
      return handle != null ? handle : handleFor(name);
    }

    public String firstName() {
      return shownName().split(" ")[0];
    }
  }

  public record Faith(String tradition, Observance observance) {}

  public record Work(String title, String employer) {}

  /** {@code with} names the partner if they're a resident; absent means an off-network partner. */
  public record Status(StatusKind kind, String with) {}

  public record FamilyLink(String with, String relation) {}

  /** A real-world tie outside the shared circles (Kristen and Deepa are neighbors). */
  public record Knows(String with, String how) {}

  /** R: the 31st member. Onboarding pre-fills these and asks for the rest (SPEC §5.9). */
  public record You(Gender gender, int age, String heritage, Faith faith, Work work, Status status) {}

  public record Pinned(String name, String archetype, Seeds seeds) {}

  /**
   * @param arcs {@code arc:phase}
   * @param tags {@code tag:param -> Other Name}
   */
  public record Seeds(List<String> arcs, List<String> tags, List<String> quirks) {
    public Seeds {
      arcs = Defs.list(arcs);
      tags = Defs.list(tags);
      quirks = Defs.list(quirks);
    }
  }

  public static String handleFor(String name) {
    String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim().replaceAll("\\s+", ".");
  }
}
