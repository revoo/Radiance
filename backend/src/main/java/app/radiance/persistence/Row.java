package app.radiance.persistence;

import tools.jackson.databind.JsonNode;

/** One result row. Primitive accessors throw on NULL; use {@link #isNull} or the nullable variants. */
public interface Row {

  /** The text value, or null. */
  String str(String c);

  long lng(String c);

  double dbl(String c);

  boolean bool(String c);

  /** The parsed JSON value, or null. */
  JsonNode json(String c);

  boolean isNull(String c);

  default Long lngOrNull(String c) {
    return isNull(c) ? null : lng(c);
  }

  default Double dblOrNull(String c) {
    return isNull(c) ? null : dbl(c);
  }

  /** The enum constant named by the column, or null. */
  default <E extends Enum<E>> E enm(String c, Class<E> type) {
    String s = str(c);
    return s == null ? null : Enum.valueOf(type, s);
  }
}
