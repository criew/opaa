package io.opaa.indexing.chunk;

import io.opaa.llm.RecordedEmbeddingIndex;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** {@link RecordedEmbeddingIndex} from the {@code embedding_index} row. */
@Component
class EmbeddingIndexRecordPort implements RecordedEmbeddingIndex {

  private final EmbeddingIndexStore store;

  EmbeddingIndexRecordPort(EmbeddingIndexStore store) {
    this.store = store;
  }

  @Override
  public Optional<Recorded> recorded() {
    return store
        .load()
        .map(
            record ->
                new Recorded(
                    record.current().model(),
                    record.target() == null ? null : record.target().model()));
  }
}
