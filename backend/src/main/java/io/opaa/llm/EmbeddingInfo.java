package io.opaa.llm;

/**
 * The currently configured embedding model (#759) - provider, model identifier and vector
 * dimensionality, read straight from the same {@code spring.ai.*}/{@code opaa.*} properties {@code
 * application.yml} already resolves for the active {@link
 * org.springframework.ai.embedding.EmbeddingModel} bean. Deliberately just a value read out of
 * configuration, not a managed entity like {@link LlmModel}: unlike the chat model, the embedding
 * model is not editable through the admin API at all (see {@link EmbeddingInfoService}).
 *
 * <p>{@code provider} is always {@code openai} since #762 - the OpenAI-compatible protocol is the
 * only connection path Spring AI wires here, Ollama included, via its own {@code /v1} endpoint. The
 * field stays part of this record (and the API response built from it) rather than being dropped,
 * since a future, genuinely different connection path is not ruled out.
 */
public record EmbeddingInfo(String provider, String model, int dimensions) {}
