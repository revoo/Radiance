package app.radiance.persistence;

import app.radiance.kernel.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import tools.jackson.databind.JsonNode;

final class JdbcRow implements Row {

  private final ResultSet rs;

  JdbcRow(ResultSet rs) {
    this.rs = rs;
  }

  @Override
  public String str(String c) {
    try {
      return rs.getString(c);
    } catch (SQLException e) {
      throw wrap(c, e);
    }
  }

  @Override
  public long lng(String c) {
    try {
      long v = rs.getLong(c);
      if (rs.wasNull()) throw nullIn(c);
      return v;
    } catch (SQLException e) {
      throw wrap(c, e);
    }
  }

  @Override
  public double dbl(String c) {
    try {
      double v = rs.getDouble(c);
      if (rs.wasNull()) throw nullIn(c);
      return v;
    } catch (SQLException e) {
      throw wrap(c, e);
    }
  }

  @Override
  public boolean bool(String c) {
    return lng(c) != 0;
  }

  @Override
  public JsonNode json(String c) {
    String s = str(c);
    return s == null ? null : Json.tree(s);
  }

  @Override
  public boolean isNull(String c) {
    try {
      return rs.getObject(c) == null;
    } catch (SQLException e) {
      throw wrap(c, e);
    }
  }

  private static IllegalStateException nullIn(String c) {
    return new IllegalStateException("column " + c + " is NULL; check isNull() first");
  }

  private static IllegalStateException wrap(String c, SQLException e) {
    return new IllegalStateException("cannot read column " + c, e);
  }
}
