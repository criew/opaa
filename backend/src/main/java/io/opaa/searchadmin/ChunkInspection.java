package io.opaa.searchadmin;

import java.util.Map;
import java.util.UUID;

/**
 * One stored chunk as the retrieval pipeline sees it - text and every metadata key of the vector
 * store row - together with the document and library it resolved to. Never carries the embedding:
 * the read path does not select the column, so no mapper can leak it by accident. {@code
 * chunkIndex} is null for a row stored without a {@code chunk_index} metadatum.
 */
public record ChunkInspection(
    String chunkId,
    UUID documentId,
    String documentTitle,
    UUID libraryId,
    String libraryName,
    Integer chunkIndex,
    String content,
    Map<String, Object> metadata) {}
