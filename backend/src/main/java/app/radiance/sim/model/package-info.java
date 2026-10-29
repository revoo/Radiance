/**
 * The engine's in-memory world: residents, edges, arcs, entities and recent items. Exposed read-only to
 * the narrative module, which builds context packets from it (SPEC §5.10).
 */
@org.springframework.modulith.NamedInterface("model")
package app.radiance.sim.model;
