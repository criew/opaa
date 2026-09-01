package io.opaa.api;

import io.opaa.api.dto.LowChunkDocumentPageResponse;
import io.opaa.api.dto.LowChunkDocumentResponse;
import io.opaa.indexing.LowChunkDocumentAuditService;
import org.springframework.data.domain.Page;

/**
 * Maps {@link LowChunkDocumentAuditService.LowChunkDocumentEntry} onto its generated response
 * counterpart (ADR-0006: API DTOs are generated from the specification, never hand-written).
 */
final class LowChunkDocumentResponseMapper {

  private LowChunkDocumentResponseMapper() {}

  static LowChunkDocumentPageResponse toPageResponse(
      Page<LowChunkDocumentAuditService.LowChunkDocumentEntry> page) {
    return new LowChunkDocumentPageResponse(
        page.getContent().stream().map(LowChunkDocumentResponseMapper::toResponse).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements());
  }

  private static LowChunkDocumentResponse toResponse(
      LowChunkDocumentAuditService.LowChunkDocumentEntry entry) {
    return new LowChunkDocumentResponse(
            entry.documentId(),
            entry.libraryId(),
            entry.libraryName(),
            entry.fileName(),
            entry.chunkCount())
        .fileSize(entry.fileSize());
  }
}
