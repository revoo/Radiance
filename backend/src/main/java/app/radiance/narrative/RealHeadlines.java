package app.radiance.narrative;

import app.radiance.news.Headline;
import app.radiance.news.HeadlineFeed;
import app.radiance.news.HeadlineIngest;
import app.radiance.news.HeadlineRepository;
import app.radiance.persistence.Sql;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Today's real news: fetches the feeds, classifies what's new, and serves what's stored (SPEC §5.12). */
public final class RealHeadlines implements HeadlineFeed {

  private static final Logger log = LoggerFactory.getLogger(RealHeadlines.class);
  private static final long CLASSIFY_WINDOW = 3 * 86_400_000L;

  private final Sql sql;
  private final HeadlineIngest ingest;
  private final HeadlineClassifier classifier;
  private final ZoneId zone;

  public RealHeadlines(Sql sql, HeadlineIngest ingest, HeadlineClassifier classifier, ZoneId zone) {
    this.sql = sql;
    this.ingest = ingest;
    this.classifier = classifier;
    this.zone = zone;
  }

  @Override
  public void refresh(long wallMs) {
    int fetched = ingest.ingest(wallMs);
    var waiting = new HeadlineRepository(sql).unclassifiedSince(wallMs - CLASSIFY_WINDOW);
    int classified = classifier.classify(waiting, wallMs);
    log.info("Headlines: {} new, {} of {} waiting classified", fetched, classified, waiting.size());
  }

  @Override
  public List<Headline> on(LocalDate date) {
    long from = date.atStartOfDay(zone).toInstant().toEpochMilli();
    long to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    return new HeadlineRepository(sql).publishedBetween(from, to);
  }
}
