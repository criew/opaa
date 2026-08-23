package io.opaa.llm;

/**
 * The currently configured embedding model (#759) - provider, model identifier and vector
 * dimensionality, read straight from the same {@code spring.ai.*}/{@code opaa.*} properties {@code
 * application.yml} already resolves for the active {@link
 * org.springframework.ai.embedding.EmbeddingModel} bean. Deliberately just a value read out of
 * configuration, not a managed entity like {@link LlmModel}: unlike the chat model, the embedding
 * model is not editable through the admin API at all (see {@link EmbeddingInfoService}).
 *
 * <p>{@code provider} is always {@code ollama} since #773 (embedding's native connection reverted
 * from Ollama's OpenAI-compatible {@code /v1/embeddings} endpoint after that endpoint caused a
 * measurable retrieval-quality regression, see issue #773) - chat remains on the {@code openai}
 * protocol, unaffected. The field stays part of this record (and the API response built from it)
 * rather than being dropped, since a future, genuinely different connection path is not ruled out.
 */
public record EmbeddingInfo(String provider, String model, int dimensions) {}
