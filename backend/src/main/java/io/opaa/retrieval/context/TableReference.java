package io.opaa.retrieval.context;

/**
 * One table of one document, as the chunks of a spreadsheet name it ({@link
 * io.opaa.format.chunk.ChunkTable}): what {@link NeighborChunkLookup#findTables} loads whole.
 */
public record TableReference(String documentId, int tableId) {

  /** The key the lookup matches rows by: {@code <document_id>#<table id>}. */
  String key() {
    return documentId + "#" + tableId;
  }
}
