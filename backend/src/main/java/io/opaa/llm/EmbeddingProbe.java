package io.opaa.llm;

import java.util.List;

/**
 * The probe embedding of an endpoint: one request for a few fixed texts, no redirect, a bounded
 * answer. The test suite replaces it with a fake that answers without a network.
 */
public interface EmbeddingProbe {

  /**
   * Embeds {@code texts} with {@code endpoint} in one request; a failure is a result, never an
   * exception. A success carries one vector per text, in order, all of the same width.
   */
  EmbeddingModelService.ProbeOutcome probe(EmbeddingEndpoint endpoint, List<String> texts);
}
