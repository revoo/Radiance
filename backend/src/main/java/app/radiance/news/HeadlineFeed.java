package app.radiance.news;

import java.time.LocalDate;
import java.util.List;

/** Where a world's classified headlines come from (SPEC §5.12): real feeds, or fixtures offline and in tests. */
public interface HeadlineFeed {

  /** Picks up what's new, if this feed changes. Slow: it can call out, so never call it on the writer lane. */
  default void refresh(long wallMs) {}

  /** Every classified headline published on {@code date}, in the world's zone. */
  List<Headline> on(LocalDate date);
}
