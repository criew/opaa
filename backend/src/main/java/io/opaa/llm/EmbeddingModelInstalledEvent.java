package io.opaa.llm;

/**
 * Published in the transaction that installed the embedding model and created the vector table;
 * listeners act after its commit.
 */
public record EmbeddingModelInstalledEvent(String modelIdentifier, int dimensions) {}
