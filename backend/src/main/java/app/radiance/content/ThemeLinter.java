package app.radiance.content;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Finds blacklisted themes in text (SPEC §5.5): each entry's {@code theme-terms}, matched
 * case-insensitively on word boundaries. A hit is a candidate; for rendered prose the linter-judge
 * route decides (M5). Genesis output with a hit is regenerated.
 */
public final class ThemeLinter {

  public record Hit(String arcId, String term) {}

  private record Rule(String arcId, String term, Pattern pattern) {}

  private final List<Rule> rules = new ArrayList<>();

  public ThemeLinter(List<ContentProperties.BlacklistEntry> blacklist) {
    for (var entry : blacklist) {
      for (String term : entry.themeTerms()) {
        String words = Pattern.quote(term.strip()).replace(" ", "\\E\\s+\\Q");
        rules.add(new Rule(entry.id(), term,
            Pattern.compile("(?<![\\p{L}\\p{N}])" + words + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)));
      }
    }
  }

  public List<Hit> hits(String text) {
    if (text == null || text.isEmpty()) return List.of();
    var out = new ArrayList<Hit>();
    for (var r : rules) if (r.pattern().matcher(text).find()) out.add(new Hit(r.arcId(), r.term()));
    return out;
  }
}
