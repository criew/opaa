package io.opaa.retrieval;

import io.opaa.api.types.RetrievalStageName;
import java.util.Set;

/**
 * What {@link RetrievalStageName#RELEVANCE_CUTOFF} decided: the cut sets the size of the context.
 * {@link RetrievalStageName#DOCUMENT_COMPLETION} fills to {@code budget} instead of {@code top-k}
 * and never draws a chunk from {@code removedChunkIds} back in.
 *
 * @param budget the number of chunks the selection held after the cut.
 * @param removedChunkIds the chunks the cut removed.
 */
public record RelevanceCut(int budget, Set<String> removedChunkIds) {

  public RelevanceCut {
    removedChunkIds = Set.copyOf(removedChunkIds);
  }
}
