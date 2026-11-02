package app.radiance.news;

import app.radiance.persistence.Sql;
import app.radiance.persistence.UnitOfWork;
import app.radiance.persistence.WriterLane;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** Stores what's new in the feeds, unclassified (SPEC §5.12). Only each feed's top stories, which also bounds the cost. */
public final class HeadlineIngest {

  static final int MAX_PER_SOURCE = 12;
  private static final long DAY = 86_400_000L;
  static final long MAX_AGE = 3 * DAY;
  private static final long DEDUPE_WINDOW = 7 * DAY;

  private final Sql sql;
  private final WriterLane lane;
  private final RssFeeds feeds;
  private final List<RssFeeds.Source> sources;

  public HeadlineIngest(Sql sql, WriterLane lane, RssFeeds feeds, List<RssFeeds.Source> sources) {
    this.sql = sql;
    this.lane = lane;
    this.feeds = feeds;
    this.sources = List.copyOf(sources);
  }

  /** Returns how many new headlines were stored. */
  public int ingest(long now) {
    var repo = new HeadlineRepository(sql);
    var urls = new HashSet<String>();
    var titles = new HashSet<String>();
    for (var h : repo.publishedSince(now - DEDUPE_WINDOW)) {
      urls.add(h.url());
      titles.add(RssFeeds.normalize(h.title()));
    }
    var fresh = feeds.fetch(sources, now, MAX_PER_SOURCE).stream()
        .filter(i -> i.publishedAt() >= now - MAX_AGE)
        .filter(i -> !urls.contains(i.url()) && !titles.contains(RssFeeds.normalize(i.title())))
        .map(i -> new Headline(id(i.url()), i.url(), i.source(), i.title(), i.snippet(), i.publishedAt(), now, null, null,
            0, 0, null, null, null))
        .toList();
    if (!fresh.isEmpty()) lane.run(() -> sql.commit(repo.save(new UnitOfWork(), fresh)));
    return fresh.size();
  }

  static String id(String url) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
      return "h_" + HexFormat.of().formatHex(hash, 0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
