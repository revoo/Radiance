package app.radiance.story;

import app.radiance.kernel.Visibility;

/** An entry in the canon ledger (SPEC P2, §5.10). */
public record FactRow(
    String id,
    String subjectId,
    String statement,
    Weight weight,
    Source source,
    Status status,
    Visibility visibility,
    String arcId,
    long validFrom,
    Long validTo,
    String originRef) {

  public enum Weight {
    MAJOR,
    MINOR
  }

  public enum Source {
    ENGINE,
    GENESIS,
    PROSE,
    USER
  }

  /** PENDING until the item that introduced it airs; VOID if that item is dropped. */
  public enum Status {
    PENDING,
    ACTIVE,
    VOID
  }
}
