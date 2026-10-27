package app.radiance.persistence;

import app.radiance.kernel.Ulid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Normalizes bound values to what both SQLite and D1 store: text, integers, reals and null. Booleans
 * become 0/1, enums their name, JSON trees their text. Anything else is a bug and fails loudly.
 */
final class SqlArgs {

  private SqlArgs() {}

  static Object normalize(Object v) {
    return switch (v) {
      case null -> null;
      case String s -> s;
      case Long l -> l;
      case Integer i -> i.longValue();
      case Short s -> s.longValue();
      case Double d -> d;
      case Float f -> f.doubleValue();
      case Boolean b -> b ? 1L : 0L;
      case Enum<?> e -> e.name();
      case JsonNode j -> j.toString();
      case Ulid u -> u.toString();
      default ->
          throw new IllegalArgumentException(
              "unsupported SQL argument type " + v.getClass().getName() + ": " + v);
    };
  }

  /** A normalized, unmodifiable copy that may contain nulls. */
  static List<Object> normalizeAll(List<?> args) {
    var out = new ArrayList<Object>(args.size());
    for (Object a : args) out.add(normalize(a));
    return Collections.unmodifiableList(out);
  }
}
