package io.opaa.indexing.metadata;

import java.util.Map;
import java.util.Set;

/**
 * The filterable metadata a document lends to every one of its chunks (ADR-0024, Entscheidung 5,
 * erweitert um Bibliotheksfelder in #1071): the values themselves and the complete set of keys this
 * document's library manages. A key of {@link #managedKeys()} that is absent from {@link #values()}
 * is removed from the chunks - which is how an emptied field stops filtering, and why the managed
 * set must list the library's fields even when none of them carries a value.
 *
 * @param values the keys with a value - the absence of a key on a chunk is the absence of the value
 * @param managedKeys every key this rewrite owns; nothing outside it is touched on a chunk
 */
public record DocumentChunkMetadata(Map<String, Object> values, Set<String> managedKeys) {

  public static final DocumentChunkMetadata EMPTY =
      new DocumentChunkMetadata(Map.of(), CoreMetadataChunkKeys.ALL);

  public DocumentChunkMetadata {
    values = Map.copyOf(values);
    managedKeys = Set.copyOf(managedKeys);
  }
}
