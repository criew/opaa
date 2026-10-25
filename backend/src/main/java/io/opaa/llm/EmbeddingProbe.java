package io.opaa.llm;

/**
 * The probe embedding of an endpoint: one request, no redirect, a bounded answer. The test suite
 * replaces it with a fake that answers without a network.
 */
public interface EmbeddingProbe {

  /** Embeds a probe text with {@code endpoint}; a failure is a result, never an exception. */
  EmbeddingModelService.ProbeOutcome probe(EmbeddingEndpoint endpoint);
}
