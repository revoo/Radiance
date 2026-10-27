package app.radiance.cast;

/** A resident's slow-changing core (SPEC §5.2). JSON columns are parsed by the world model (M4). */
public record PersonaRow(
    String actorId,
    String traitsJson,
    String quirksJson,
    String interestsJson,
    String politicsJson,
    String biasesJson,
    String tasteJson,
    String temperamentJson,
    String voiceJson,
    String routineJson,
    String valuesJson,
    String fearsJson,
    String desiresJson,
    String offNetworkJson,
    String identityJson,
    String backstory,
    String lifeSummary,
    boolean pinned) {}
