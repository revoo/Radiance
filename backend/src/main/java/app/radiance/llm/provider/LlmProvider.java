package app.radiance.llm.provider;

/**
 * One wire protocol (SPEC §5.10). Adapters turn a {@link ProviderRequest} into the provider's JSON,
 * and its response, refusals and errors into a {@link ProviderResponse}. They never throw for
 * provider-side failures; they report an outcome. No provider type leaves an adapter.
 */
public interface LlmProvider {

  ProviderResponse complete(ProviderRequest request);
}
