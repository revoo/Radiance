package app.radiance.world;

/**
 * Genesis has imported a world (SPEC §6.4). The batch module answers by running the first batch, which
 * backfills the world's history and writes the first script, so the first login has content.
 */
public record GenesisImported(long seed, int residents) {}
