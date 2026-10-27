package app.radiance.llm.provider;

import app.radiance.llm.Block;
import app.radiance.llm.LlmProperties;
import app.radiance.llm.Part;
import app.radiance.llm.Purpose;
import java.time.Duration;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

/**
 * What an adapter sends: the prefix (including the alias's prompt notes, already appended by the
 * gateway), the variable content, and the output schema. How the schema travels depends on the model's
 * {@code structured-output} capability, which only the adapter acts on.
 */
public record ProviderRequest(
    Purpose purpose,
    String alias,
    LlmProperties.Model model,
    LlmProperties.Provider provider,
    List<Block> prefix,
    List<Part> content,
    Class<?> outputType,
    ObjectNode schema,
    String schemaName,
    int maxOutputTokens,
    Duration timeout) {}
