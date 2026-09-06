package io.opaa.indexing.metadata;

import io.opaa.indexing.ChunkContextPrefix;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The metadata a document lends to every one of its chunks (ADR-0024, Entscheidung 5, erweitert um
 * Bibliotheksfelder in #1071 und den Kontextpräfix in #1072): the filterable values themselves, the
 * complete set of keys this document's library manages, and the parts that go into the chunk's
 * Kontextpräfix. A key of {@link #managedKeys()} that is absent from {@link #values()} is removed
 * from the chunks - which is how an emptied field stops filtering, and why the managed set must
 * list the library's fields even when none of them carries a value.
 *
 * @param values the keys with a value - the absence of a key on a chunk is the absence of the value
 * @param managedKeys every key this rewrite owns; nothing outside it is touched on a chunk
 * @param contextTitle the Kernfeld Titel, which replaces the file-name humanisation in the prefix;
 *     {@code null} when the document has none and the caller's own fallback applies
 * @param contextPrefixValues the prefix-effective field values in schema order, core fields first
 */
public record DocumentChunkMetadata(
    Map<String, Object> values,
    Set<String> managedKeys,
    String contextTitle,
    List<String> contextPrefixValues) {

  public static final DocumentChunkMetadata EMPTY =
      new DocumentChunkMetadata(Map.of(), CoreMetadataChunkKeys.ALL, null, List.of());

  public DocumentChunkMetadata {
    values = Map.copyOf(values);
    managedKeys = Set.copyOf(managedKeys);
    contextPrefixValues = List.copyOf(contextPrefixValues);
  }

  /**
   * The fingerprint of this document's prefix under {@code effectiveTitle} - what a chunk write
   * records and what the Nachlauf compares against. Takes the effective title rather than {@link
   * #contextTitle()} because the ingest's own fallback (a humanised file name) is part of the
   * prefix and therefore part of its identity.
   */
  public String contextPrefixStamp(String effectiveTitle) {
    return ChunkContextPrefix.stampOf(effectiveTitle, contextPrefixValues);
  }
}
