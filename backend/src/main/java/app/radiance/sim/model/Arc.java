package app.radiance.sim.model;

import app.radiance.kernel.Visibility;
import app.radiance.story.ArcRow.Status;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** An arc instance (SPEC §5.5): committed state, or its planning copy. */
public final class Arc {

  public final String id;
  public final String defId;
  public Status status;
  public String phase;
  public Visibility visibility;
  public final long startedAt;
  public long phaseEnteredAt;
  public Long nextTransitionAt;
  public Long lastBeatAt;
  /** role → actor id */
  public final TreeMap<String, String> roles;
  public final LinkedHashMap<String, Object> data;
  public String summary;
  public Long endedAt;
  public String endEventId;
  public boolean dirty;
  public boolean isNew;

  public Arc(String id, String defId, Status status, String phase, Visibility visibility, long startedAt,
      long phaseEnteredAt, Long nextTransitionAt, Long lastBeatAt, Map<String, String> roles, Map<String, Object> data,
      String summary, Long endedAt, String endEventId) {
    this.id = id;
    this.defId = defId;
    this.status = status;
    this.phase = phase;
    this.visibility = visibility;
    this.startedAt = startedAt;
    this.phaseEnteredAt = phaseEnteredAt;
    this.nextTransitionAt = nextTransitionAt;
    this.lastBeatAt = lastBeatAt;
    this.roles = new TreeMap<>(roles);
    this.data = new LinkedHashMap<>(data);
    this.summary = summary;
    this.endedAt = endedAt;
    this.endEventId = endEventId;
  }

  public Arc copy() {
    var a = new Arc(id, defId, status, phase, visibility, startedAt, phaseEnteredAt, nextTransitionAt, lastBeatAt,
        roles, data, summary, endedAt, endEventId);
    a.isNew = isNew;
    return a;
  }

  public boolean active() {
    return status == Status.ACTIVE;
  }

  public boolean involves(String actorId) {
    return roles.containsValue(actorId);
  }

  /** The role {@code actorId} plays, or null. */
  public String roleOf(String actorId) {
    for (var e : roles.entrySet()) if (e.getValue().equals(actorId)) return e.getKey();
    return null;
  }
}
