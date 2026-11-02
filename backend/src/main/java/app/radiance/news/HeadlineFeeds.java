package app.radiance.news;

import java.time.ZoneId;

/** Makes a world's {@link HeadlineFeed}. */
@FunctionalInterface
public interface HeadlineFeeds {

  HeadlineFeed forWorld(long seed, ZoneId zone);
}
