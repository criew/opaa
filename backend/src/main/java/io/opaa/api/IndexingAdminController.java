package io.opaa.api;

import io.opaa.api.dto.LowChunkDocumentPageResponse;
import io.opaa.api.dto.PipelineReindexRequest;
import io.opaa.api.dto.PipelineReindexResponse;
import io.opaa.api.dto.PipelineVersionStatusResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.DocumentPipelineRegistry;
import io.opaa.indexing.LowChunkDocumentAuditService;
import io.opaa.indexing.PipelineReindexService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only endpoints for indexing operational state, {@code SYSTEM_ADMIN} only - the same access
 * bar {@link LlmModelController} already establishes. Every one of them scopes to the caller's own
 * organization, never to a request parameter.
 */
@RestController
@RequestMapping("/api/v1/admin/indexing")
public class IndexingAdminController {

  /** Matches {@code PipelineReindexRequest.batchSize}'s own bounds in the OpenAPI specification. */
  private static final int MAX_REINDEX_BATCH_SIZE = 100;

  private static final int DEFAULT_REINDEX_BATCH_SIZE = 10;

  private final LowChunkDocumentAuditService lowChunkDocumentAuditService;
  private final PipelineReindexService pipelineReindexService;
  private final DocumentPipelineRegistry pipelineRegistry;

  public IndexingAdminController(
      LowChunkDocumentAuditService lowChunkDocumentAuditService,
      PipelineReindexService pipelineReindexService,
      DocumentPipelineRegistry pipelineRegistry) {
    this.lowChunkDocumentAuditService = lowChunkDocumentAuditService;
    this.pipelineReindexService = pipelineReindexService;
    this.pipelineRegistry = pipelineRegistry;
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

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/pipeline-versions")
  public PipelineVersionStatusResponse getPipelineVersionStatus(@Caller CurrentUser caller) {
    return PipelineVersionResponseMapper.toStatusResponse(
        pipelineRegistry.pipelines(),
        pipelineReindexService.progressForOrganization(caller.organizationId()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/pipeline-reindex")
  public PipelineReindexResponse reindexPipelineBatch(
      @RequestBody PipelineReindexRequest request, @Caller CurrentUser caller) {
    // Validated here rather than left to the service: every user-facing API error is German
    // (AGENTS.md, Projektsprache), and an unbekannte pipelineId would otherwise silently return
    // "done" for a re-index that never had a chance of matching anything.
    String pipelineId = request.getPipelineId();
    boolean known =
        pipelineRegistry.pipelines().stream()
            .anyMatch(pipeline -> pipeline.id().equals(pipelineId));
    if (!known) {
      throw new IllegalArgumentException("Unbekannte Pipeline: " + pipelineId);
    }
    Integer belowVersion = request.getBelowVersion();
    if (belowVersion == null || belowVersion < 1) {
      throw new IllegalArgumentException(
          "belowVersion muss mindestens 1 sein, war " + belowVersion);
    }
    int batchSize =
        request.getBatchSize() == null ? DEFAULT_REINDEX_BATCH_SIZE : request.getBatchSize();
    if (batchSize < 1 || batchSize > MAX_REINDEX_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "batchSize muss zwischen 1 und " + MAX_REINDEX_BATCH_SIZE + " liegen, war " + batchSize);
    }
    return PipelineVersionResponseMapper.toReindexResponse(
        pipelineReindexService.reindexBatch(
            caller.organizationId(), pipelineId, belowVersion, batchSize));
  }
}
