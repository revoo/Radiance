package app.radiance.sim;

import app.radiance.kernel.Fidelity;

/** A slice of world time and the fidelity it's written at; a null fidelity is structure only (SPEC §5.1). */
public record Slice(long start, long end, Fidelity fidelity) {

  public double hours() {
    return (end - start) / 3_600_000.0;
  }
}
