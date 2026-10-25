package io.opaa;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Fake embedding model that returns deterministic embeddings for testing. A test can interleave
 * work with an embedding round trip through {@link #beforeEmbeddingTextContaining}; {@link
 * #reset()} drops a hook that never fired.
 */
public class FakeEmbeddingModel implements EmbeddingModel {

  /** The width of every vector this fake returns. */
  public static final int DIMENSIONS = 1536;

  private record Hook(String marker, Runnable action) {}

  private final AtomicReference<Hook> hook = new AtomicReference<>();

  /**
   * Runs {@code action} once, on the calling thread, before the first embedding request with a text
   * containing {@code marker} is answered - the window between a caller's read and its write. The
   * texts are what {@link #getEmbeddingContent} yields, the stored chunk text without any
   * Kontextpräfix, so the marker must come from that text.
   */
  public void beforeEmbeddingTextContaining(String marker, Runnable action) {
    hook.set(new Hook(marker, action));
  }

  public void reset() {
    hook.set(null);
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    runHookFor(request.getInstructions());
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
    runHookFor(texts);
    return texts.stream().map(t -> generateFakeEmbedding()).toList();
  }

  private void runHookFor(List<String> texts) {
    Hook pending = hook.get();
    if (pending == null
        || texts.stream().noneMatch(text -> text != null && text.contains(pending.marker()))
        || !hook.compareAndSet(pending, null)) {
      return;
    }
    pending.action().run();
  }

  /** The width of every vector; asks nothing, as the installed model does. */
  @Override
  public int dimensions() {
    return DIMENSIONS;
  }

  private float[] generateFakeEmbedding() {
    float[] embedding = new float[DIMENSIONS];
    for (int i = 0; i < embedding.length; i++) {
      embedding[i] = (float) Math.sin(i * 0.01);
    }
    return embedding;
  }
}
