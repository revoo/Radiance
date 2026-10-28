package app.radiance.messenger;

import app.radiance.kernel.AirStatus;
import app.radiance.kernel.RenderStatus;

/** DM rows (SPEC §4.3, §5.11): a thread between two actors, and its messages. */
public final class DmRow {

  private DmRow() {}

  /** {@code aId} sorts before {@code bId}, so a pair has one thread. */
  public record Thread(String id, String aId, String bId, String summary, Long lastMessageAt) {}

  /** {@code createdAt} is the air time. */
  public record Message(
      String id, String threadId, String senderId, long createdAt, AirStatus airStatus, String body,
      RenderStatus renderStatus, String briefJson, String appraisalJson, Long readAt) {}
}
