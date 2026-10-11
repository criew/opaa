package io.opaa.retrieval.context;

import io.opaa.indexing.chunk.VectorChunkStore;
import java.util.Optional;
import org.springframework.ai.document.Document;

/**
 * Where a chunk sits in its document: the {@code document_id} and {@code chunk_index} metadata the
 * citation marker names, too.
 */
public record ChunkPosition(String documentId, int chunkIndex) {

  /** The position {@code offset} chunks away in the same document. */
  public ChunkPosition shifted(int offset) {
    return new ChunkPosition(documentId, chunkIndex + offset);
  }

  /** The key the lookup matches rows by: {@code <document_id>#<chunk_index>}. */
  public String key() {
    return documentId + "#" + chunkIndex;
  }

  /**
   * The position of {@code chunk}, or empty when it carries no document id or no integral chunk
   * index - such a chunk has no neighbours anyone could name.
   */
  public static Optional<ChunkPosition> of(Document chunk) {
    Object documentId = chunk.getMetadata().get(VectorChunkStore.DOCUMENT_ID_METADATA_KEY);
    Object chunkIndex = chunk.getMetadata().get(VectorChunkStore.CHUNK_INDEX_METADATA_KEY);
    if (documentId == null || documentId.toString().isBlank() || chunkIndex == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new ChunkPosition(documentId.toString(), Integer.parseInt(chunkIndex.toString().trim())));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
