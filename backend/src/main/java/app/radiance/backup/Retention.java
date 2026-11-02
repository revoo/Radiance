package app.radiance.backup;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Which backups to keep (ADR 0019): the newest of each of the last {@code daily} days that have one, then
 * the newest of each of the {@code weekly} ISO weeks before those. Names that aren't backups are never
 * touched, and the newest backup always stays.
 */
public final class Retention {

  private static final Pattern NAME = Pattern.compile("radiance-(\\d{8}T\\d{6}Z)\\.db\\.gz$");
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  private Retention() {}

  public static String fileName(Instant at) {
    return "radiance-" + STAMP.format(at) + ".db.gz";
  }

  /** When a backup was taken, from its name; empty if the name isn't a backup's. */
  public static Optional<Instant> takenAt(String name) {
    var m = NAME.matcher(name);
    if (!m.find()) return Optional.empty();
    return Optional.of(Instant.from(STAMP.parse(m.group(1))));
  }

  /** The newest backup among {@code names}, if any. */
  public static Optional<String> newest(List<String> names) {
    return names.stream().filter(n -> takenAt(n).isPresent()).max(Comparator.comparing(n -> takenAt(n).orElseThrow()));
  }

  /** The names to delete. */
  public static List<String> toDelete(List<String> names, int daily, int weekly) {
    var backups = new ArrayList<>(names.stream().filter(n -> takenAt(n).isPresent()).toList());
    backups.sort(Comparator.comparing((String n) -> takenAt(n).orElseThrow()).reversed());
    var keep = new HashSet<String>();
    var days = new HashSet<LocalDate>();
    var weeks = new HashSet<String>();
    for (String n : backups) {
      var date = takenAt(n).orElseThrow().atZone(ZoneOffset.UTC).toLocalDate();
      if (days.contains(date)) continue; // an older backup of a day that's already kept
      if (days.size() < daily) {
        days.add(date);
        keep.add(n);
        continue;
      }
      String week = date.get(IsoFields.WEEK_BASED_YEAR) + "-" + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
      if (!weeks.contains(week) && weeks.size() < weekly) {
        weeks.add(week);
        keep.add(n);
      }
    }
    if (!backups.isEmpty()) keep.add(backups.getFirst());
    return backups.stream().filter(n -> !keep.contains(n)).toList();
  }
}
