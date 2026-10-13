package io.opaa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;

/**
 * The hook of {@link FakeEmbeddingModel} sees what a chunk write embeds through it: the stored
 * chunk text, not the Kontextpräfix a formatter puts in front of it. A marker taken from the prefix
 * never fires - the reason a race test must pick its marker from the stored text.
 */
class FakeEmbeddingModelTest {

  private final FakeEmbeddingModel model = new FakeEmbeddingModel();

  @Test
  void theHookFiresOnTheStoredTextOnceAndNeverOnThePrefix() {
    AtomicInteger fromText = new AtomicInteger();
    AtomicInteger fromPrefix = new AtomicInteger();

    model.beforeEmbeddingTextContaining("Personalausweises", fromText::incrementAndGet);
    embedPrefixedChunk();
    embedPrefixedChunk();
    model.beforeEmbeddingTextContaining("Meldewesen", fromPrefix::incrementAndGet);
    embedPrefixedChunk();

    assertThat(fromText).hasValue(1);
    assertThat(fromPrefix).hasValue(0);
  }

  @Test
  void resetDropsAHookThatNeverFired() {
    AtomicInteger fired = new AtomicInteger();
    model.beforeEmbeddingTextContaining("Personalausweises", fired::incrementAndGet);

    model.reset();
    embedPrefixedChunk();

    assertThat(fired).hasValue(0);
  }

  private void embedPrefixedChunk() {
    Document chunk = new Document("Gebühr für die Ausstellung eines Personalausweises.");
    chunk.setContentFormatter(
        (document, mode) -> "[Gebührenordnung Meldewesen]\n\n" + document.getText());
    model.embed(
        List.of(chunk), EmbeddingOptions.builder().build(), new TokenCountBatchingStrategy());
  }
}
