package app.radiance.narrative;

import java.util.List;

/** The {@code compaction} route's output (SPEC §5.7): 1–3 period summaries and a new "lately". */
public record Compacted(List<String> periodSummaries, String lifeSummary) {}
