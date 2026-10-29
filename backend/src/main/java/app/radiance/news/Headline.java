package app.radiance.news;

import java.util.List;
import java.util.Map;

/**
 * A classified headline (SPEC §4.3, §5.12). Only the title, link, source, publish time and a short
 * snippet are ever stored; never articles.
 *
 * @param region LOCAL, NATIONAL or WORLD
 * @param sensitivity NORMAL, SENSITIVE or TRAGIC
 * @param axisSignal per political axis ({@code economic}, {@code social}), −1…1: which side is likelier
 *     to cheer (negative: left or progressive)
 */
public record Headline(
    String id, String url, String source, String title, String snippet, long publishedAt, long fetchedAt,
    String region, List<String> topics, double controversy, double salience, String sensitivity,
    Map<String, Double> axisSignal, Long classifiedAt) {

  public Headline {
    topics = topics == null ? List.of() : List.copyOf(topics);
    axisSignal = axisSignal == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(axisSignal));
  }

  public boolean tragic() {
    return "TRAGIC".equals(sensitivity);
  }
}
