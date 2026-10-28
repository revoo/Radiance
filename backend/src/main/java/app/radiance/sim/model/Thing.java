package app.radiance.sim.model;

import app.radiance.story.EntityRow.Kind;
import java.util.LinkedHashMap;
import java.util.Map;

/** A story entity — the car, the house, the dog — with its canonical look (SPEC §5.5, §5.13). */
public final class Thing {

  public final String id;
  public final String ownerId;
  public final Kind kind;
  public final String name;
  public final String visualDescription;
  public final Long imageSeed;
  public final LinkedHashMap<String, Object> attributes;
  public String status;
  public final long createdAt;
  public long updatedAt;
  public boolean dirty;

  public Thing(String id, String ownerId, Kind kind, String name, String visualDescription, Long imageSeed,
      Map<String, Object> attributes, String status, long createdAt, long updatedAt) {
    this.id = id;
    this.ownerId = ownerId;
    this.kind = kind;
    this.name = name;
    this.visualDescription = visualDescription;
    this.imageSeed = imageSeed;
    this.attributes = new LinkedHashMap<>(attributes);
    this.status = status;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public Thing copy() {
    return new Thing(id, ownerId, kind, name, visualDescription, imageSeed, attributes, status, createdAt, updatedAt);
  }

  /** Owned and not sold, broken beyond use, finished or gone. */
  public boolean current() {
    return "OWNED".equals(status) || "ACTIVE".equals(status) || "IN_PROGRESS".equals(status);
  }
}
