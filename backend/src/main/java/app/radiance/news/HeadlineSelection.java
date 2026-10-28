package app.radiance.news;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which headlines a world-day's script may use (SPEC §5.12): the top {@code headlines-per-sim-day} by
 * salience, split by {@code region-mix}. A region short of headlines gives its slots to the others.
 */
public final class HeadlineSelection {

  private HeadlineSelection() {}

  public static List<Headline> select(List<Headline> available, int perDay, Map<String, Double> regionMix) {
    var byRegion = new LinkedHashMap<String, List<Headline>>();
    for (var h : available.stream().sorted(Comparator.comparingDouble(Headline::salience).reversed()
        .thenComparing(Headline::id)).toList()) {
      byRegion.computeIfAbsent(h.region(), k -> new ArrayList<>()).add(h);
    }
    var out = new ArrayList<Headline>();
    var leftovers = new ArrayList<Headline>();
    var quotas = quotas(perDay, regionMix);
    for (var e : regionMix.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      int quota = quotas.get(e.getKey());
      var list = byRegion.getOrDefault(e.getKey(), List.of());
      out.addAll(list.subList(0, Math.min(quota, list.size())));
      if (list.size() > quota) leftovers.addAll(list.subList(quota, list.size()));
    }
    leftovers.sort(Comparator.comparingDouble(Headline::salience).reversed().thenComparing(Headline::id));
    for (var h : leftovers) {
      if (out.size() >= perDay) break;
      out.add(h);
    }
    out.sort(Comparator.comparingLong(Headline::publishedAt).thenComparing(Headline::id));
    return out;
  }

  /** Whole slots per region that add up to {@code n}: largest remainders, ties to the larger share. */
  public static Map<String, Integer> quotas(int n, Map<String, Double> mix) {
    double total = mix.values().stream().mapToDouble(Double::doubleValue).sum();
    var out = new LinkedHashMap<String, Integer>();
    int given = 0;
    for (var e : mix.entrySet()) {
      int q = (int) Math.floor(n * e.getValue() / total);
      out.put(e.getKey(), q);
      given += q;
    }
    var order = mix.entrySet().stream()
        .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(e -> {
              double exact = n * e.getValue() / total;
              return -(exact - Math.floor(exact));
            })
            .thenComparing(e -> -e.getValue()).thenComparing(Map.Entry::getKey))
        .map(Map.Entry::getKey).toList();
    for (int i = 0; given < n && !order.isEmpty(); i++, given++) out.merge(order.get(i % order.size()), 1, Integer::sum);
    return out;
  }
}
