package io.opaa.api;

import io.opaa.api.dto.LowChunkDocumentPageResponse;
import io.opaa.api.dto.PipelineReindexRequest;
import io.opaa.api.dto.PipelineReindexResponse;
import io.opaa.api.dto.PipelineVersionStatusResponse;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.LowChunkDocumentAuditService;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import io.opaa.indexing.pipeline.PipelineReindexResult;
import io.opaa.indexing.pipeline.PipelineReindexService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
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
  private final AuditEventRecorder auditEventRecorder;

  public IndexingAdminController(
      LowChunkDocumentAuditService lowChunkDocumentAuditService,
      PipelineReindexService pipelineReindexService,
      DocumentPipelineRegistry pipelineRegistry,
      AuditEventRecorder auditEventRecorder) {
    this.lowChunkDocumentAuditService = lowChunkDocumentAuditService;
    this.pipelineReindexService = pipelineReindexService;
    this.pipelineRegistry = pipelineRegistry;
    this.auditEventRecorder = auditEventRecorder;
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
    // (AGENTS.md, Projektsprache), and an unknown pipelineId would otherwise silently return
    // "done" for a re-index that never had a chance of matching anything.
    String pipelineId = request.getPipelineId();
    DocumentPipeline pipeline =
        pipelineRegistry.pipelines().stream()
            .filter(candidate -> candidate.id().equals(pipelineId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unbekannte Pipeline: " + pipelineId));
    Integer belowVersion = request.getBelowVersion();
    if (belowVersion == null || belowVersion < 1) {
      throw new IllegalArgumentException(
          "belowVersion muss mindestens 1 sein, war " + belowVersion);
    }
    // Above the pipeline's own version there is no version to re-index *to*: the run would rewrite
    // every chunk at the current version, find it still below the requested bound, and select the
    // same documents again on every following batch - an unbounded loop of embedding calls, not a
    // slow run. Note that a chunk selected via the routing gap (#1105, still naming the fallback
    // pipeline for a format pipelineId now claims) is included regardless of this bound - see
    // PipelineReindexService#selectStaleDocuments.
    if (belowVersion > pipeline.version()) {
      throw new IllegalArgumentException(
          "belowVersion darf höchstens der aktuellen Version der Pipeline "
              + pipelineId
              + " entsprechen ("
              + pipeline.version()
              + "), war "
              + belowVersion);
    }
    int batchSize =
        request.getBatchSize() == null ? DEFAULT_REINDEX_BATCH_SIZE : request.getBatchSize();
    if (batchSize < 1 || batchSize > MAX_REINDEX_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "batchSize muss zwischen 1 und " + MAX_REINDEX_BATCH_SIZE + " liegen, war " + batchSize);
    }

    PipelineReindexResult result =
        pipelineReindexService.reindexBatch(
            caller.organizationId(), pipelineId, belowVersion, batchSize);
    recordReindexAudit(caller, pipelineId, belowVersion, result);
    return PipelineVersionResponseMapper.toReindexResponse(result);
  }

  /**
   * Records the triggering call, not one event per document: the call is the administrative
   * decision, the documents are its effect. The object is the pipeline itself, identified by a
   * name-derived UUID the way {@code AuditRetentionSettingsService} already identifies a settings
   * object that has no row of its own.
   */
  private void recordReindexAudit(
      CurrentUser caller, String pipelineId, int belowVersion, PipelineReindexResult result) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(caller.organizationId())
            .actor(caller.id())
            .type(AuditEventType.INDEXING_PIPELINE_REINDEX_TRIGGERED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes(pipelineId.getBytes(StandardCharsets.UTF_8)),
                "Ingestion-Pipeline " + pipelineId)
            .after(
                Map.of(
                    "belowVersion", belowVersion,
                    "reindexedDocuments", result.reindexedDocuments(),
                    "markedForNextRun", result.markedForNextRun(),
                    "skippedDocuments", result.skippedDocuments(),
                    "removedOrphanChunkSets", result.removedOrphanChunkSets()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
