package io.opaa.llm;

/**
 * The currently configured embedding model (#759) - provider, model identifier and vector
 * dimensionality, read straight from the same {@code spring.ai.*}/{@code opaa.*} properties {@code
 * application.yml} already resolves for the active {@link
 * org.springframework.ai.embedding.EmbeddingModel} bean. Deliberately just a value read out of
 * configuration, not a managed entity like {@link LlmModel}: unlike the chat model, the embedding
 * model is not editable through the admin API at all (see {@link EmbeddingInfoService}).
 */
public record EmbeddingInfo(String provider, String model, int dimensions) {}
