package io.opaa.llm;

/**
 * The installed client of {@code generation} embedded successfully while its model row has no
 * reference anchor; published at most once per cached client.
 */
public record EmbeddingAnchorMissingEvent(long generation) {}
