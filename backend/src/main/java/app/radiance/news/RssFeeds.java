package app.radiance.news;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Reads RSS 2.0 and Atom feeds (SPEC §5.12). Only the title, link, source, publish time and a short snippet are
 * kept, and articles are never fetched. Feeds are outside content: XML entities are refused and responses capped.
 */
public final class RssFeeds {

  /** A configured feed. */
  public record Source(String name, String url) {}

  /** One unclassified story from a feed. */
  public record Item(String url, String source, String title, String snippet, long publishedAt) {}

  private static final Logger log = LoggerFactory.getLogger(RssFeeds.class);
  private static final int MAX_BYTES = 8 * 1024 * 1024;
  private static final int MAX_SNIPPET = 300;
  private static final Pattern OUTLET_SUFFIX = Pattern.compile(
      " [-–—] \\p{Lu}[\\p{L}\\p{Nd}.'’&-]*( (\\p{Lu}[\\p{L}\\p{Nd}.'’&-]*|of|the|and|for|&)){0,5}$");

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NORMAL).build();

  /** Every source's items, deduped; a source that fails is logged and skipped. */
  public List<Item> fetch(List<Source> sources, long now) {
    return fetch(sources, now, Integer.MAX_VALUE);
  }

  /** The same, keeping only the first {@code maxPerSource} of each feed: the ones it ranks highest. */
  public List<Item> fetch(List<Source> sources, long now, int maxPerSource) {
    var out = new ArrayList<Item>();
    for (var source : sources) {
      try {
        out.addAll(parse(get(source.url()), source.name(), now).stream().limit(maxPerSource).toList());
      } catch (Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        log.warn("Feed '{}' failed: {}", source.name(), e.toString());
      }
    }
    return dedupe(out);
  }

  private byte[] get(String url) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
        .header("User-Agent", "Mozilla/5.0 (compatible; Radiance/1.0)").GET().build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (var body = response.body()) {
      if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
      byte[] bytes = body.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) throw new IllegalStateException("larger than " + MAX_BYTES + " bytes");
      return bytes;
    }
  }

  static List<Item> parse(byte[] xml, String source, long now) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    factory.setNamespaceAware(true);
    var builder = factory.newDocumentBuilder();
    builder.setErrorHandler(null);
    var root = builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
    var out = new ArrayList<Item>();
    for (var entry : entries(root)) {
      String title = clean(text(entry, "title"));
      // aggregators name the real outlet, and add it to the end of the title too
      String outlet = clean(text(entry, "source"));
      if (!outlet.isEmpty() && title.endsWith(" - " + outlet)) title = title.substring(0, title.length() - outlet.length() - 3);
      String url = link(entry);
      if (title.isEmpty() || url == null) continue;
      String snippet = clean(firstNonBlank(text(entry, "description"), text(entry, "summary"), text(entry, "content")));
      if (normalize(snippet).startsWith(normalize(title))) snippet = "";
      if (snippet.length() > MAX_SNIPPET) snippet = snippet.substring(0, MAX_SNIPPET - 1).stripTrailing() + "…";
      out.add(new Item(url, outlet.isEmpty() ? source : outlet, title, snippet.isEmpty() ? null : snippet, published(entry, now)));
    }
    return out;
  }

  private static List<Element> entries(Element root) {
    var out = new ArrayList<Element>();
    collect(root, out);
    return out;
  }

  private static void collect(Element parent, List<Element> out) {
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (!(n instanceof Element e)) continue;
      if ("item".equals(e.getLocalName()) || "entry".equals(e.getLocalName())) out.add(e);
      else if ("channel".equals(e.getLocalName())) collect(e, out);
    }
  }

  /** The direct child's text, whatever its namespace, or an empty string. */
  private static String text(Element parent, String name) {
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e && name.equals(e.getLocalName())) return e.getTextContent();
    }
    return "";
  }

  private static String link(Element entry) {
    for (Node n = entry.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (!(n instanceof Element e) || !"link".equals(e.getLocalName())) continue;
      String rel = e.getAttribute("rel");
      if (!rel.isEmpty() && !"alternate".equals(rel)) continue;
      String href = e.getAttribute("href");
      String url = (href.isEmpty() ? e.getTextContent() : href).strip();
      if (url.startsWith("https://") || url.startsWith("http://")) return url;
    }
    return null;
  }

  private static long published(Element entry, long now) {
    for (String name : List.of("pubDate", "published", "updated", "date")) {
      long at = parseDate(text(entry, name).strip());
      if (at > 0) return Math.min(at, now);
    }
    return now;
  }

  static long parseDate(String s) {
    if (s.isEmpty()) return 0;
    for (var format : List.of(DateTimeFormatter.RFC_1123_DATE_TIME, DateTimeFormatter.ISO_OFFSET_DATE_TIME)) {
      try {
        return OffsetDateTime.parse(s, format).toInstant().toEpochMilli();
      } catch (DateTimeParseException ignored) {
        // try the next format
      }
    }
    try {
      return Instant.parse(s).toEpochMilli();
    } catch (DateTimeParseException e) {
      return 0;
    }
  }

  private static String firstNonBlank(String... values) {
    for (var v : values) {
      if (!v.isBlank()) return v;
    }
    return "";
  }

  /** Feed text often carries markup and escaped entities; we keep plain text. */
  static String clean(String s) {
    String out = s.replaceAll("(?s)<[^>]*>", " ")
        .replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&#039;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&#8217;", "’").replace("&#8216;", "‘").replace("&#8220;", "“").replace("&#8221;", "”")
        .replace("&#8211;", "–").replace("&#8212;", "—").replace("&#8230;", "…");
    return out.replaceAll("\\s+", " ").strip();
  }

  /** The first of each story, by URL and by normalized title: the same story often comes from several feeds. */
  public static List<Item> dedupe(List<Item> items) {
    Set<String> urls = new HashSet<>();
    Set<String> titles = new HashSet<>();
    var out = new ArrayList<Item>();
    for (var item : items) {
      boolean newUrl = urls.add(item.url());
      boolean newTitle = titles.add(normalize(item.title()));
      if (newUrl && newTitle) out.add(item);
    }
    return out;
  }

  /** Lowercase letters and digits, without the " - Outlet" that aggregators add to the end. */
  static String normalize(String title) {
    var outlet = OUTLET_SUFFIX.matcher(title);
    String kept = outlet.find() ? title.substring(0, outlet.start()) : title;
    return kept.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", " ").strip();
  }
}
