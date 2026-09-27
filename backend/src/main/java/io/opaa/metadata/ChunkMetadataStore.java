package io.opaa.metadata;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The chunk store as metadata needs it: rewriting document-level keys on every chunk of a document
 * without touching content or embedding (ADR-0024). Implemented by the index, which lies above this
 * package.
 */
public interface ChunkMetadataStore {

  /**
   * Sets {@code values} and removes {@code keysToClear} on every chunk of {@code documentId}.
   *
   * @return the number of chunks updated
   */
  int updateDocumentMetadata(UUID documentId, Map<String, Object> values, Set<String> keysToClear);
}
