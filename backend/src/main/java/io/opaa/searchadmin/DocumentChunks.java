package io.opaa.searchadmin;

import java.util.List;
import java.util.UUID;

/**
 * Every stored chunk of one document in {@code chunk_index} order. {@code chunkCount} is the number
 * the document entity recorded at indexing time, deliberately kept next to the actual list so a gap
 * between the two is visible rather than papered over.
 */
public record DocumentChunks(
    UUID documentId,
    String documentTitle,
    UUID libraryId,
    String libraryName,
    int chunkCount,
    List<ChunkInspection> chunks) {}
