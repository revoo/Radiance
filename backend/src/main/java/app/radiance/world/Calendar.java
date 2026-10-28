package app.radiance.world;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.TreeSet;

/**
 * The calendar triggers a world-day carries (SPEC §5.4, §5.14, §6.1): federal holidays, cultural and
 * Chicago dates, the cast's religious observances and the seasons. Birthdays are per resident and
 * checked with {@link #isBirthday}. Lunar and lunisolar dates come from a table (2026–2030); the
 * Easters are computed.
 */
public final class Calendar {

  private Calendar() {}

  /** First days, by year: [passover, ramadan start, eid al-fitr, rosh hashanah, yom kippur, hanukkah, diwali]. */
  static final Map<Integer, String[]> LUNAR = Map.of(
      2026, new String[] {"04-02", "02-18", "03-20", "09-12", "09-21", "12-05", "11-08"},
      2027, new String[] {"04-22", "02-08", "03-10", "10-02", "10-11", "12-25", "10-29"},
      2028, new String[] {"04-11", "01-28", "02-27", "09-21", "09-30", "12-13", "10-17"},
      2029, new String[] {"03-31", "01-16", "02-15", "09-10", "09-19", "12-02", "11-05"},
      2030, new String[] {"04-18", "01-06", "02-05", "09-28", "10-07", "12-21", "10-26"});

  public static String season(LocalDate d) {
    return switch (d.getMonthValue()) {
      case 12, 1, 2 -> "winter";
      case 3, 4, 5 -> "spring";
      case 6, 7, 8 -> "summer";
      default -> "fall";
    };
  }

  public static boolean isBirthday(String birthDate, LocalDate d) {
    if (birthDate == null) return false;
    var b = LocalDate.parse(birthDate);
    var md = MonthDay.from(b);
    if (md.equals(MonthDay.of(2, 29)) && !d.isLeapYear()) return d.getMonthValue() == 2 && d.getDayOfMonth() == 28;
    return MonthDay.from(d).equals(md);
  }

  public static TreeSet<String> triggers(LocalDate d) {
    var out = new TreeSet<String>();
    int y = d.getYear();
    fixed(out, d, 1, 1, "new_years_day");
    fixed(out, d, 12, 31, "new_years_eve");
    fixed(out, d, 2, 14, "valentines_day");
    fixed(out, d, 3, 17, "st_patricks_day");
    fixed(out, d, 6, 19, "juneteenth");
    fixed(out, d, 7, 4, "fourth_of_july");
    fixed(out, d, 10, 31, "halloween");
    fixed(out, d, 11, 11, "veterans_day");
    fixed(out, d, 12, 24, "christmas_eve");
    fixed(out, d, 12, 25, "christmas");
    fixed(out, d, 3, 20, "first_day_of_spring");
    fixed(out, d, 6, 21, "first_day_of_summer");
    fixed(out, d, 9, 22, "first_day_of_fall");
    fixed(out, d, 12, 21, "first_day_of_winter");
    if (d.equals(nth(y, 1, DayOfWeek.MONDAY, 3))) out.add("mlk_day");
    if (d.equals(nth(y, 2, DayOfWeek.SUNDAY, 2))) out.add("super_bowl_sunday");
    if (d.equals(nth(y, 2, DayOfWeek.MONDAY, 3))) out.add("presidents_day");
    if (d.equals(nth(y, 3, DayOfWeek.MONDAY, 1))) out.add("pulaski_day");
    if (d.equals(LocalDate.of(y, 3, 17).with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)))) {
      out.add("st_patricks_parade");
    }
    if (d.equals(nth(y, 5, DayOfWeek.SUNDAY, 2))) out.add("mothers_day");
    if (d.equals(LocalDate.of(y, 5, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))) {
      out.add("memorial_day");
    }
    if (d.equals(nth(y, 6, DayOfWeek.SUNDAY, 3))) out.add("fathers_day");
    var lolla = nth(y, 8, DayOfWeek.THURSDAY, 1);
    if (!d.isBefore(lolla) && !d.isAfter(lolla.plusDays(3))) out.add("lollapalooza");
    if (d.equals(nth(y, 8, DayOfWeek.SATURDAY, 2))) out.add("bud_billiken_parade");
    if (d.equals(nth(y, 8, DayOfWeek.SATURDAY, 3))) out.add("air_and_water_show");
    if (d.equals(nth(y, 8, DayOfWeek.MONDAY, 3))) out.add("back_to_school");
    if (d.equals(nth(y, 9, DayOfWeek.MONDAY, 1))) out.add("labor_day");
    if (d.equals(LocalDate.of(y, 9, 10).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)))) {
      out.add("mexican_independence_parade");
    }
    if (d.equals(nth(y, 10, DayOfWeek.SUNDAY, 2))) out.add("chicago_marathon");
    if (y % 2 == 0 && d.equals(nth(y, 11, DayOfWeek.MONDAY, 1).plusDays(1))) out.add("election_day");
    if (d.equals(nth(y, 11, DayOfWeek.THURSDAY, 4))) out.add("thanksgiving");
    var easter = easter(y);
    if (d.equals(easter)) out.add("easter");
    if (d.equals(orthodoxEaster(y))) out.add("orthodox_easter");
    if (d.getDayOfWeek() == DayOfWeek.FRIDAY && d.isAfter(easter.minusDays(47)) && d.isBefore(easter)) {
      out.add("lent_friday");
    }
    var lunar = LUNAR.get(y);
    if (lunar != null) {
      if (d.equals(day(y, lunar[0]))) out.add("passover");
      if (!d.isBefore(day(y, lunar[1])) && d.isBefore(day(y, lunar[2]))) out.add("ramadan");
      if (d.equals(day(y, lunar[2]))) out.add("eid_al_fitr");
      if (d.equals(day(y, lunar[3]))) out.add("rosh_hashanah");
      if (d.equals(day(y, lunar[4]))) out.add("yom_kippur");
      if (d.equals(day(y, lunar[5]))) out.add("hanukkah");
      if (d.equals(day(y, lunar[6]))) out.add("diwali");
    }
    return out;
  }

  /** Gregorian Easter (the anonymous Gregorian algorithm). */
  static LocalDate easter(int y) {
    int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
    int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
    int m = (a + 11 * h + 22 * l) / 451;
    int month = (h + l - 7 * m + 114) / 31, day = (h + l - 7 * m + 114) % 31 + 1;
    return LocalDate.of(y, month, day);
  }

  /** Orthodox Easter: the Julian computus, shifted to the Gregorian calendar (+13 days, 1900–2099). */
  static LocalDate orthodoxEaster(int y) {
    int a = y % 4, b = y % 7, c = y % 19, d = (19 * c + 15) % 30, e = (2 * a + 4 * b - d + 34) % 7;
    int month = (d + e + 114) / 31, day = (d + e + 114) % 31 + 1;
    return LocalDate.of(y, month, day).plusDays(13);
  }

  private static LocalDate nth(int y, int month, DayOfWeek dow, int n) {
    return LocalDate.of(y, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow));
  }

  private static LocalDate day(int y, String mmdd) {
    return LocalDate.parse(y + "-" + mmdd);
  }

  private static void fixed(TreeSet<String> out, LocalDate d, int m, int day, String id) {
    if (d.getMonthValue() == m && d.getDayOfMonth() == day) out.add(id);
  }
}
