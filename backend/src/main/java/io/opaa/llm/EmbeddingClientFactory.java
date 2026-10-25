package io.opaa.llm;

import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Builds the client of an embedding endpoint. The one place a client for the installed model and
 * for a probe comes from, so the test suite replaces both with one fake.
 */
public interface EmbeddingClientFactory {

  /** A client for {@code endpoint}; building it sends nothing. */
  EmbeddingModel create(EmbeddingEndpoint endpoint);
}
