package app.radiance.llm.provider;

import app.radiance.llm.Block;
import tools.jackson.databind.node.ObjectNode;

/**
 * For models without schema mode ({@code JSON} or {@code NONE}), the schema travels in the prompt
 * (SPEC §5.10). It goes at the end of the prefix: it is fixed per purpose, so it stays cacheable.
 */
public final class SchemaPrompt {

  private SchemaPrompt() {}

  public static Block block(ObjectNode schema) {
    return new Block(
        "Output format: reply with a single JSON object and nothing else — no code fences, no"
            + " commentary. It must match this JSON Schema exactly (every field is required):\n"
            + schema.toPrettyString());
  }
}
