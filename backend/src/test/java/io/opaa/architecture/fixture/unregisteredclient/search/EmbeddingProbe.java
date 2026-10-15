package io.opaa.architecture.fixture.unregisteredclient.search;

import org.springframework.ai.embedding.EmbeddingModel;

/** Sends text to the embedding model bean without registering the data flow. */
public class EmbeddingProbe {

  private final EmbeddingModel embeddingModel;

  public EmbeddingProbe(EmbeddingModel embeddingModel) {
    this.embeddingModel = embeddingModel;
  }

  public float[] probe() {
    return embeddingModel.embed("probe");
  }
}
