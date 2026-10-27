package app.radiance.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A participant selector in an event def (SPEC §5.4): {@code friend[affinity>40]},
 * {@code circle:softball[familiarity>=20,affinity>0]}, {@code anyone}. Parsed at load so typos fail
 * startup; the planner (M4) evaluates it.
 */
public record Selector(String relation, String param, List<Condition> conditions) {

  public static final Set<String> RELATIONS = Set.of(
      "anyone", "friend", "close_friend", "best_friend", "family", "coworker", "partner", "ex",
      "rival", "crush", "circle", "neighbor", "connected", "stranger", "user");

  public static final Set<String> DIMENSIONS =
      Set.of("affinity", "trust", "attraction", "familiarity", "respect", "chemistry");

  public record Condition(String dimension, String op, double value) {}

  private static final Pattern SHAPE =
      Pattern.compile("([a-z_]+)(?::([a-z0-9_-]+))?(?:\\[(.*)])?");
  private static final Pattern COND = Pattern.compile("([a-z_]+)\\s*(>=|<=|>|<)\\s*(-?\\d+(?:\\.\\d+)?)");

  public static Selector parse(String text) {
    var m = SHAPE.matcher(text.strip());
    if (!m.matches()) throw new IllegalArgumentException("not a selector: '" + text + "'");
    String relation = m.group(1);
    if (!RELATIONS.contains(relation)) {
      throw new IllegalArgumentException("unknown relation '" + relation + "' in '" + text + "'");
    }
    if ("circle".equals(relation) && (m.group(2) == null || !Vocab.CIRCLES.contains(m.group(2)))) {
      throw new IllegalArgumentException("circle selectors need a known circle: '" + text + "'");
    }
    var conditions = new ArrayList<Condition>();
    if (m.group(3) != null) {
      for (String part : m.group(3).split(",")) {
        var c = COND.matcher(part.strip());
        if (!c.matches() || !DIMENSIONS.contains(c.group(1))) {
          throw new IllegalArgumentException("bad condition '" + part.strip() + "' in '" + text + "'");
        }
        conditions.add(new Condition(c.group(1), c.group(2), Double.parseDouble(c.group(3))));
      }
    }
    return new Selector(relation, m.group(2), List.copyOf(conditions));
  }
}
