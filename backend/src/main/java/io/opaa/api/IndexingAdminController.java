package io.opaa.api;

import io.opaa.api.dto.LowChunkDocumentPageResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.LowChunkDocumentAuditService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only read endpoints for indexing operational state, {@code SYSTEM_ADMIN} only - the same
 * access bar {@link LlmModelController} already establishes. {@link #listLowChunkDocuments} always
 * scopes to the caller's own organization, never a request parameter.
 */
@RestController
@RequestMapping("/api/v1/admin/indexing")
public class IndexingAdminController {

  private final LowChunkDocumentAuditService lowChunkDocumentAuditService;

  public IndexingAdminController(LowChunkDocumentAuditService lowChunkDocumentAuditService) {
    this.lowChunkDocumentAuditService = lowChunkDocumentAuditService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/low-chunk-documents")
  public LowChunkDocumentPageResponse listLowChunkDocuments(
      @RequestParam(defaultValue = "" + LowChunkDocumentAuditService.DEFAULT_CHUNK_COUNT_THRESHOLD)
          int chunkCountThreshold,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @Caller CurrentUser caller) {
    // Validated here, not left to PageRequest.of's own IllegalArgumentException, whose English
    // message would otherwise reach the response body verbatim via GlobalExceptionHandler - every
    // user-facing API error is German (AGENTS.md, Projektsprache).
    if (page < 0) {
      throw new IllegalArgumentException("page darf nicht negativ sein, war " + page);
    }
    if (size < 1 || size > 100) {
      throw new IllegalArgumentException("size muss zwischen 1 und 100 liegen, war " + size);
    }
    Pageable pageable =
        PageRequest.of(
            page, size, Sort.by(Sort.Order.asc("libraryId"), Sort.Order.asc("fileName")));
    return LowChunkDocumentResponseMapper.toPageResponse(
        lowChunkDocumentAuditService.findLowChunkDocuments(
            caller.organizationId(), chunkCountThreshold, pageable));
  }
}
