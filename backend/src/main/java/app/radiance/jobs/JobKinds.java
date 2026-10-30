package app.radiance.jobs;

/** The job kinds (SPEC §5.10, §5.15); the module that handles a kind registers it with the runner. */
public final class JobKinds {

  private JobKinds() {}

  /** Render a post and its scripted comments: ref = post id. */
  public static final String RENDER_THREAD = "RENDER_THREAD";
  /** Render the scripted DMs in a thread: ref = thread id. */
  public static final String RENDER_DM = "RENDER_DM";
  /** Compact an actor's old memories: ref = actor id. */
  public static final String REFLECT = "REFLECT";
  /** Refresh how one person sees another: ref = {@code from|to}. */
  public static final String SUMMARY = "SUMMARY";
  /** Summarize a DM thread's older messages: ref = thread id. */
  public static final String DM_SUMMARY = "DM_SUMMARY";

  /** Lower runs first (SPEC §5.10): script, then backfill, then background work. */
  public static final int SCRIPT = 10;
  public static final int BACKFILL = 20;
  public static final int BACKGROUND = 30;
}
