package io.opaa;

import java.util.List;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** Fake embedding model that returns deterministic embeddings for testing. */
public class FakeEmbeddingModel implements EmbeddingModel {

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    List<Embedding> embeddings =
        request.getInstructions().stream()
            .map(
                text ->
                    new Embedding(generateFakeEmbedding(), request.getInstructions().indexOf(text)))
            .toList();
    return new EmbeddingResponse(embeddings);
  }

  @Override
  public float[] embed(org.springframework.ai.document.Document document) {
    return generateFakeEmbedding();
  }

  @Override
  public List<float[]> embed(List<String> texts) {
    return texts.stream().map(t -> generateFakeEmbedding()).toList();
  }

  private float[] generateFakeEmbedding() {
    float[] embedding = new float[1536];
    for (int i = 0; i < embedding.length; i++) {
      embedding[i] = (float) Math.sin(i * 0.01);
    }
    return embedding;
  }
}
