package app.radiance.persistence;

import java.util.ArrayList;
import java.util.List;

/** Splits a migration file into statements, ignoring semicolons in comments and string literals. */
final class SqlScript {

  private SqlScript() {}

  static List<String> split(String script) {
    var statements = new ArrayList<String>();
    var current = new StringBuilder();
    int n = script.length();
    for (int i = 0; i < n; i++) {
      char c = script.charAt(i);
      char next = i + 1 < n ? script.charAt(i + 1) : '\0';
      if (c == '-' && next == '-') {
        while (i < n && script.charAt(i) != '\n') i++;
        current.append('\n');
      } else if (c == '/' && next == '*') {
        int end = script.indexOf("*/", i + 2);
        if (end < 0) throw new IllegalArgumentException("unterminated block comment");
        i = end + 1;
        current.append(' ');
      } else if (c == '\'' || c == '"') {
        int j = i + 1;
        while (true) {
          if (j >= n) throw new IllegalArgumentException("unterminated quoted text");
          if (script.charAt(j) == c) {
            if (j + 1 < n && script.charAt(j + 1) == c) {
              j += 2; // doubled quote is an escaped quote
              continue;
            }
            break;
          }
          j++;
        }
        current.append(script, i, j + 1);
        i = j;
      } else if (c == ';') {
        addIfPresent(statements, current);
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    addIfPresent(statements, current);
    return statements;
  }

  private static void addIfPresent(List<String> out, StringBuilder sb) {
    String s = sb.toString().strip();
    if (!s.isEmpty()) out.add(s);
  }
}
