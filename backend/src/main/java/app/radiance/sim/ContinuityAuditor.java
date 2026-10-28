package app.radiance.sim;

import app.radiance.content.ContentLibrary;
import app.radiance.kernel.Visibility;
import app.radiance.sim.model.SimWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks the §5.5 invariants a planning slice could break (SPEC §5.1 step 6): throws in tests and dev,
 * records findings in prod. Run on the planning copy before and after the slice's items apply.
 */
public final class ContinuityAuditor {

  public enum Mode { THROW, RECORD }

  public static final class ContinuityViolation extends RuntimeException {
    public ContinuityViolation(String message) {
      super(message);
    }
  }

  private final ContentLibrary lib;
  private final Mode mode;
  public final List<String> findings = new ArrayList<>();

  public ContinuityAuditor(ContentLibrary lib, Mode mode) {
    this.lib = lib;
    this.mode = mode;
  }

  /** Before the slice applies: no contradictions (3) and secrets hold (7). */
  void beforeApply(SimWorld w, Script s) {
    for (var e : s.events) {
      if (e.arcId() != null || e.defId() == null || e.actorId() == null) continue;
      var def = lib.events().get(e.defId());
      if (def == null) continue;
      var tags = w.activeTags(e.actorId(), e.worldTime(), lib);
      for (String t : def.excludes().tags()) {
        if (Eligibility.hasTag(tags, t)) flag("3 no contradictions: " + def.id() + " for " + e.actorId() + " under " + t);
      }
      if (Eligibility.blocked(def, tags, lib)) flag("3 no contradictions: " + def.id() + " blocked by a constraint for " + e.actorId());
    }
    for (var p : s.posts) {
      if (p.arcId() == null) continue;
      var arc = w.arcs.get(p.arcId());
      if (arc != null && arc.visibility == Visibility.SECRET && !arc.involves(p.authorId())
          && !w.knows("ARC", arc.id, p.authorId())) {
        flag("7 secrets hold: " + p.authorId() + " posted about secret arc " + arc.id);
      }
    }
  }

  /** After the slice applies to the copy. */
  void afterApply(SimWorld w, long sliceEnd) {
    for (var a : w.arcs.values()) {
      var def = lib.arcs().get(a.defId);
      if (!a.active()) {
        if (a.endEventId == null) flag("1 no silent endings: arc " + a.id + " ended without an event");
        continue;
      }
      if (lib.isBlacklisted(a.defId) && (def == null || def.abandon() == null)) {
        flag("10 blacklist holds: arc " + a.id + " (" + a.defId + ") is active");
      }
      if (def == null) continue;
      if (a.visibility == Visibility.PUBLIC) {
        long last = Math.max(a.lastBeatAt == null ? a.phaseEnteredAt : a.lastBeatAt, a.phaseEnteredAt);
        if (sliceEnd - last > (def.maxSilenceDays() + 1) * 86_400_000L) {
          flag("2 visible continuity: arc " + a.id + " silent since " + last);
        }
      }
      if (a.nextTransitionAt != null && a.nextTransitionAt < sliceEnd - 86_400_000L) {
        flag("6 time sanity: arc " + a.id + " overdue in " + a.phase);
      }
      Object entity = a.data.get("entity");
      if (entity != null && !w.things.containsKey(String.valueOf(entity))) {
        flag("5 entities persist: arc " + a.id + " lost entity " + entity);
      }
      if (Set.of("romance", "affair").contains(a.defId)) {
        var ids = List.copyOf(a.roles.values());
        if (ids.size() == 2 && w.person(ids.get(0)).gender == w.person(ids.get(1)).gender) {
          flag("11 roster holds: " + a.defId + " " + a.id + " pairs two people of the same gender");
        }
      }
      for (var b : w.arcs.values()) {
        if (b == a || !b.active() || b.id.compareTo(a.id) < 0) continue;
        if (def.exclusiveWith().contains(b.defId)) {
          for (String id : a.roles.values()) {
            if (b.involves(id)) flag("4 exclusivity: " + id + " is in " + a.defId + " and " + b.defId);
          }
        }
      }
    }
    for (var e : w.edges.values()) {
      if (e.attraction <= 0) continue;
      var a = w.people.get(e.from);
      var b = w.people.get(e.to);
      if (a != null && b != null && a.gender == b.gender) flag("11 roster holds: attraction " + e.from + " -> " + e.to);
    }
  }

  private void flag(String finding) {
    findings.add(finding);
    if (mode == Mode.THROW) throw new ContinuityViolation(finding);
  }
}
