package app.radiance.llm;

import java.util.Arrays;

/**
 * What a call is for (SPEC §5.10). Domain code names a purpose; configuration decides which model
 * serves it. The config key is the kebab-case name ({@code render-thread}).
 */
public enum Purpose {
  RENDER_THREAD,
  RENDER_DM,
  LIVE_COMMENT,
  APPRAISAL,
  VISION_APPRAISAL,
  HEADLINE_CLASSIFY,
  LINTER_JUDGE,
  COMPACTION,
  SUMMARY,
  RECAP,
  GENESIS;

  public String key() {
    return name().toLowerCase().replace('_', '-');
  }

  /** Capabilities every model on this purpose's route must declare. */
  public boolean needsVision() {
    return this == VISION_APPRAISAL;
  }

  public static Purpose fromKey(String key) {
    return Arrays.stream(values())
        .filter(p -> p.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown LLM purpose: " + key));
  }
}
