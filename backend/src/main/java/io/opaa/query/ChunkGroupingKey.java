package io.opaa.query;

import org.springframework.ai.document.Document;

/**
 * Groups a chunk by its {@code document_id} metadata, falling back to {@code file_name} when that
 * metadata is missing or empty - a chunk without {@code document_id} can only occur for pre-#739
 * index entries, since {@code DocumentIngestService#storeChunks} now writes it on every chunk. The
 * {@code file:} prefix on the fallback keeps two such chunks from <em>different</em> documents from
 * merging into one entry via a shared empty-string key.
 */
final class ChunkGroupingKey {

  private ChunkGroupingKey() {}

  static String of(Document chunk) {
    String documentId = chunk.getMetadata().getOrDefault("document_id", "").toString();
    if (!documentId.isEmpty()) {
      return documentId;
    }
    return "file:" + chunk.getMetadata().getOrDefault("file_name", "unknown").toString();
  }
}
