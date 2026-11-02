package app.radiance.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Feed parsing, on the shapes of the real feeds checked 2026-11-02 (Google News, WordPress, Atom). */
class RssFeedsTests {

  static final long NOW = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli();

  static final String GOOGLE = """
      <?xml version="1.0" encoding="UTF-8"?>
      <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>Top stories</title>
      <item><title>Senate passes the budget - The Hill</title><link>https://news.google.com/rss/articles/abc?oc=5</link>
      <pubDate>Mon, 02 Nov 2026 10:30:00 GMT</pubDate>
      <description>&lt;a href="https://news.google.com/rss/articles/abc"&gt;Senate passes the budget&lt;/a&gt;&amp;nbsp;&lt;font&gt;The Hill&lt;/font&gt;</description>
      <source url="https://thehill.com">The Hill</source></item>
      </channel></rss>
      """;

  static final String WORDPRESS = """
      <?xml version="1.0" encoding="UTF-8"?>
      <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/"
           xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel>
      <item><title>New bike lanes &amp; a fight on Milwaukee Ave</title><link>https://blockclubchicago.org/lanes/</link>
      <pubDate>Mon, 02 Nov 2026 04:00:00 -0600</pubDate>
      <description><![CDATA[<p>Neighbors packed the meeting&#8230; <b>again</b>.</p>]]></description>
      <content:encoded><![CDATA[<p>The whole article, which we must not keep.</p>]]></content:encoded>
      </item>
      <item><title></title><link>https://example.com/no-title</link></item>
      <item><title>No link</title></item>
      <item><title>Bad link</title><link>javascript:alert(1)</link></item>
      </channel></rss>
      """;

  static final String ATOM = """
      <?xml version="1.0" encoding="utf-8"?>
      <feed xmlns="http://www.w3.org/2005/Atom"><title>World</title>
      <entry><title>Quake hits the coast</title>
      <link rel="self" href="https://example.com/self"/><link rel="alternate" href="https://example.com/quake"/>
      <published>2026-11-02T09:00:00Z</published><updated>2026-11-02T11:00:00Z</updated>
      <summary>Rescue teams are on the way.</summary></entry>
      </feed>
      """;

  static List<RssFeeds.Item> parse(String xml) throws Exception {
    return RssFeeds.parse(xml.getBytes(StandardCharsets.UTF_8), "Test", NOW);
  }

  @Test
  void readsAGoogleNewsItemAndDropsASnippetThatOnlyRepeatsTheTitle() throws Exception {
    var item = parse(GOOGLE).getFirst();
    assertThat(item.title()).as("the outlet comes off the end").isEqualTo("Senate passes the budget");
    assertThat(item.url()).isEqualTo("https://news.google.com/rss/articles/abc?oc=5");
    assertThat(item.publishedAt()).isEqualTo(Instant.parse("2026-11-02T10:30:00Z").toEpochMilli());
    assertThat(item.source()).as("the real outlet, not the aggregator").isEqualTo("The Hill");
    assertThat(item.snippet()).isNull();
  }

  @Test
  void readsAWordPressItemAsPlainTextAndNeverKeepsTheArticle() throws Exception {
    var items = parse(WORDPRESS);
    assertThat(items).as("items with no title or a bad link are skipped").hasSize(1);
    var item = items.getFirst();
    assertThat(item.title()).isEqualTo("New bike lanes & a fight on Milwaukee Ave");
    assertThat(item.publishedAt()).isEqualTo(Instant.parse("2026-11-02T10:00:00Z").toEpochMilli());
    assertThat(item.snippet()).isEqualTo("Neighbors packed the meeting… again .");
  }

  @Test
  void readsAtomAndPicksTheArticleLink() throws Exception {
    var item = parse(ATOM).getFirst();
    assertThat(item.url()).isEqualTo("https://example.com/quake");
    assertThat(item.publishedAt()).isEqualTo(Instant.parse("2026-11-02T09:00:00Z").toEpochMilli());
    assertThat(item.snippet()).isEqualTo("Rescue teams are on the way.");
  }

  @Test
  void snippetsAreCappedAt300Characters() throws Exception {
    var xml = "<rss><channel><item><title>T</title><link>https://e.com/a</link><description>"
        + "x".repeat(900) + "</description></item></channel></rss>";
    assertThat(parse(xml).getFirst().snippet()).hasSize(300).endsWith("…");
  }

  @Test
  void aMissingBadOrFuturePublishTimeBecomesNow() throws Exception {
    var xml = "<rss><channel>"
        + "<item><title>A</title><link>https://e.com/a</link></item>"
        + "<item><title>B</title><link>https://e.com/b</link><pubDate>yesterday-ish</pubDate></item>"
        + "<item><title>C</title><link>https://e.com/c</link><pubDate>Fri, 01 Jan 2027 00:00:00 GMT</pubDate></item>"
        + "</channel></rss>";
    assertThat(parse(xml)).extracting(RssFeeds.Item::publishedAt).containsOnly(NOW);
  }

  @Test
  void refusesEntitiesSoAFeedCantReadLocalFiles(@TempDir Path dir) throws Exception {
    var secret = dir.resolve("secret.txt");
    Files.writeString(secret, "TOP-SECRET");
    var xxe = """
        <?xml version="1.0"?><!DOCTYPE rss [<!ENTITY x SYSTEM "%s">]>
        <rss><channel><item><title>&x;</title><link>https://e.com/a</link></item></channel></rss>
        """.formatted(secret.toUri());
    assertThatThrownBy(() -> parse(xxe)).hasMessageContaining("DOCTYPE");
    var bomb = """
        <?xml version="1.0"?><!DOCTYPE r [<!ENTITY a "aaaa"><!ENTITY b "&a;&a;&a;&a;">]>
        <rss><channel><item><title>&b;</title><link>https://e.com/a</link></item></channel></rss>
        """;
    assertThatThrownBy(() -> parse(bomb)).hasMessageContaining("DOCTYPE");
  }

  @Test
  void dedupesByUrlAndByTitleAcrossOutlets() {
    var items = List.of(
        new RssFeeds.Item("https://a.com/1", "A", "Senate passes the budget - The Hill", null, NOW),
        new RssFeeds.Item("https://b.com/2", "B", "SENATE passes the budget!", null, NOW),
        new RssFeeds.Item("https://a.com/1", "C", "A different title, same link", null, NOW),
        new RssFeeds.Item("https://c.com/3", "D", "Budget fight - what comes next", null, NOW));
    assertThat(RssFeeds.dedupe(items)).extracting(RssFeeds.Item::source).containsExactly("A", "D");
  }

  @Test
  void aDashInTheFirstHalfOfATitleIsNotAnOutlet() {
    assertThat(RssFeeds.normalize("Budget fight - what comes next")).isEqualTo("budget fight what comes next");
    assertThat(RssFeeds.normalize("Bears lose again - Chicago Sun-Times")).isEqualTo("bears lose again");
  }

  @Test
  void overRealHttpAFailingFeedDoesntStopTheOthers() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/good", ex -> respond(ex, 200, ATOM));
    server.createContext("/down", ex -> respond(ex, 503, "busy"));
    server.createContext("/html", ex -> respond(ex, 200, "<html><body>not a feed<br></body></html>"));
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var items = new RssFeeds().fetch(List.of(new RssFeeds.Source("Down", base + "/down"),
          new RssFeeds.Source("Html", base + "/html"), new RssFeeds.Source("Good", base + "/good")), NOW);
      assertThat(items).extracting(RssFeeds.Item::source).containsExactly("Good");
    } finally {
      server.stop(0);
    }
  }

  private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }
}
