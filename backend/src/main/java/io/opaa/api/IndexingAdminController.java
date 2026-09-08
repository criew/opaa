package io.opaa.api;

import io.opaa.api.dto.ContextPrefixRerunRequest;
import io.opaa.api.dto.ContextPrefixRerunResponse;
import io.opaa.api.dto.LowChunkDocumentPageResponse;
import io.opaa.api.dto.MetadataBackfillRequest;
import io.opaa.api.dto.MetadataBackfillResponse;
import io.opaa.api.dto.PipelineReindexRequest;
import io.opaa.api.dto.PipelineReindexResponse;
import io.opaa.api.dto.PipelineVersionStatusResponse;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditQueryService;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.ContextPrefixRerunResult;
import io.opaa.indexing.ContextPrefixRerunService;
import io.opaa.indexing.LowChunkDocumentAuditService;
import io.opaa.indexing.PipelineReindexResult;
import io.opaa.indexing.PipelineReindexService;
import io.opaa.indexing.metadata.CoreMetadataExtractor;
import io.opaa.indexing.metadata.MetadataBackfillResult;
import io.opaa.indexing.metadata.MetadataBackfillService;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(IndexingAdminController.class);

  private final LowChunkDocumentAuditService lowChunkDocumentAuditService;
  private final PipelineReindexService pipelineReindexService;
  private final MetadataBackfillService metadataBackfillService;
  private final ContextPrefixRerunService contextPrefixRerunService;
  private final DocumentPipelineRegistry pipelineRegistry;
  private final AuditEventRecorder auditEventRecorder;

  public IndexingAdminController(
      LowChunkDocumentAuditService lowChunkDocumentAuditService,
      PipelineReindexService pipelineReindexService,
      MetadataBackfillService metadataBackfillService,
      ContextPrefixRerunService contextPrefixRerunService,
      DocumentPipelineRegistry pipelineRegistry,
      AuditEventRecorder auditEventRecorder) {
    this.lowChunkDocumentAuditService = lowChunkDocumentAuditService;
    this.pipelineReindexService = pipelineReindexService;
    this.metadataBackfillService = metadataBackfillService;
    this.contextPrefixRerunService = contextPrefixRerunService;
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

  /**
   * One batch of the pipeline re-index. Every call - executed or rejected - leaves exactly one
   * audit event, see {@link #audited}.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/pipeline-reindex")
  public PipelineReindexResponse reindexPipelineBatch(
      @RequestBody PipelineReindexRequest request, @Caller CurrentUser caller) {
    String pipelineId = request.getPipelineId();
    int batchSize = effectiveBatchSize(request.getBatchSize());
    Map<String, Object> requested = new LinkedHashMap<>();
    requested.put("pipelineId", pipelineId);
    requested.put("belowVersion", request.getBelowVersion());
    requested.put("batchSize", batchSize);
    PipelineReindexResult result =
        audited(
            caller,
            AuditEventType.INDEXING_PIPELINE_REINDEX_TRIGGERED,
            AuditObjectType.SYSTEM_SETTING,
            pipelineObjectId(pipelineId),
            "Ingestion-Pipeline " + (pipelineId == null ? "(keine Angabe)" : pipelineId),
            requested,
            () -> {
              // Validated here rather than left to the service: every user-facing API error is
              // German (AGENTS.md, Projektsprache), and an unknown pipelineId would otherwise
              // silently return "done" for a re-index that never had a chance of matching anything.
              DocumentPipeline pipeline =
                  pipelineRegistry.pipelines().stream()
                      .filter(candidate -> candidate.id().equals(pipelineId))
                      .findFirst()
                      .orElseThrow(
                          () -> new IllegalArgumentException("Unbekannte Pipeline: " + pipelineId));
              Integer belowVersion = request.getBelowVersion();
              if (belowVersion == null || belowVersion < 1) {
                throw new IllegalArgumentException(
                    "belowVersion muss mindestens 1 sein, war " + belowVersion);
              }
              // Above the pipeline's own version there is no version to re-index *to*: the run
              // would rewrite every chunk at the current version, find it still below the requested
              // bound, and select the same documents again on every following batch - an unbounded
              // loop of embedding calls, not a slow run. Note that a chunk selected via the routing
              // gap (#1105, still naming the fallback pipeline for a format pipelineId now claims)
              // is included regardless of this bound - see
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
              return pipelineReindexService.reindexBatch(
                  caller.organizationId(), pipelineId, belowVersion, requireBatchSize(batchSize));
            },
            outcome ->
                Map.of(
                    "reindexedDocuments", outcome.reindexedDocuments(),
                    "markedForNextRun", outcome.markedForNextRun(),
                    "skippedDocuments", outcome.skippedDocuments(),
                    "removedOrphanChunkSets", outcome.removedOrphanChunkSets()));
    return PipelineVersionResponseMapper.toReindexResponse(result);
  }

  /**
   * One batch of the deterministic core-metadata backfill (#1067). The library is the explicit,
   * library-wise release the specification demands; a library of another organization is absent
   * (404), decided in the service. Pausing is not calling again; the next call resumes.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/metadata-backfill")
  public MetadataBackfillResponse backfillMetadataBatch(
      @RequestBody MetadataBackfillRequest request, @Caller CurrentUser caller) {
    UUID libraryId = requireLibraryId(request.getLibraryId());
    int batchSize = effectiveBatchSize(request.getBatchSize());
    Map<String, Object> requested = new LinkedHashMap<>();
    requested.put("extractionVersion", CoreMetadataExtractor.EXTRACTION_VERSION);
    requested.put("batchSize", batchSize);
    MetadataBackfillResult result =
        audited(
            caller,
            AuditEventType.INDEXING_METADATA_BACKFILL_TRIGGERED,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            libraryId,
            "Bibliothek " + libraryId,
            requested,
            () ->
                metadataBackfillService.backfillBatch(
                    caller.organizationId(), libraryId, requireBatchSize(batchSize)),
            outcome ->
                Map.of(
                    "processedDocuments", outcome.processedDocuments(),
                    "markedForNextRun", outcome.markedForNextRun(),
                    "skippedDocuments", outcome.skippedDocuments()));
    return MetadataBackfillResponseMapper.toBackfillResponse(result);
  }

  /**
   * One batch of the Kontextpräfix-Nachlauf (#1072). The library is the explicit, library-wise
   * release the specification demands; saving a schema change never starts it. Pausing is not
   * calling again; the next call resumes at the next unprocessed document.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/context-prefix-rerun")
  public ContextPrefixRerunResponse rerunContextPrefixBatch(
      @RequestBody ContextPrefixRerunRequest request, @Caller CurrentUser caller) {
    UUID libraryId = requireLibraryId(request.getLibraryId());
    int batchSize = effectiveBatchSize(request.getBatchSize());
    Map<String, Object> requested = new LinkedHashMap<>();
    requested.put("batchSize", batchSize);
    ContextPrefixRerunResult result =
        audited(
            caller,
            AuditEventType.INDEXING_CONTEXT_PREFIX_RERUN_TRIGGERED,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            libraryId,
            "Bibliothek " + libraryId,
            requested,
            () ->
                contextPrefixRerunService.rerunBatch(
                    caller.organizationId(), libraryId, requireBatchSize(batchSize)),
            outcome ->
                Map.of(
                    "processedDocuments", outcome.processedDocuments(),
                    "skippedDocuments", outcome.skippedDocuments()));
    return MetadataBackfillResponseMapper.toRerunResponse(result);
  }

  /**
   * The one audit mechanism of the three batch endpoints: the triggering call is the administrative
   * decision and is recorded exactly once, whether it ran, was rejected or broke off. {@code
   * SUCCESS} carries {@code requested} plus the counters derived from the result. Any {@code
   * RuntimeException} from {@code call} yields {@code FAILURE} with {@code requested} alone as
   * {@code after} and the exception message - a German validation message for a rejected call, a
   * technical one for a run that broke off mid-batch - capped to {@code audit_log.reason}'s width
   * as reason; documents a broken-off batch already committed are not visible in that event. The
   * exception is rethrown unchanged, even if writing the event itself fails, in which case that
   * failure is logged and attached as suppressed. No transaction surrounds this method, so the
   * event commits on its own regardless of what {@code call} rolled back.
   */
  private <R> R audited(
      CurrentUser caller,
      AuditEventType type,
      AuditObjectType objectType,
      UUID objectId,
      String objectLabel,
      Map<String, Object> requested,
      Supplier<R> call,
      Function<R, Map<String, Object>> counters) {
    AuditEvent.Builder event =
        AuditEvent.builder()
            .organizationId(caller.organizationId())
            .actor(caller.id())
            .type(type)
            .object(objectType, objectId, objectLabel);
    R result;
    try {
      result = call.get();
    } catch (RuntimeException ex) {
      try {
        auditEventRecorder.recordUserAction(
            event
                .after(withoutNullValues(requested))
                .outcome(AuditOutcome.FAILURE)
                .reason(capReason(ex.getMessage()))
                .build());
      } catch (RuntimeException loggingFailure) {
        log.error(
            "Failed to write the audit event for a rejected {} call - the rejection is still"
                + " reported correctly, but this call is missing its audit_log entry",
            type,
            loggingFailure);
        ex.addSuppressed(loggingFailure);
      }
      throw ex;
    }
    Map<String, Object> after = withoutNullValues(requested);
    after.putAll(counters.apply(result));
    auditEventRecorder.recordUserAction(event.after(after).outcome(AuditOutcome.SUCCESS).build());
    return result;
  }

  /**
   * {@code audit_log.reason} is bounded by {@link AuditQueryService#MAX_REASON_LENGTH}; an overlong
   * technical message would otherwise fail the insert and cost the call its one entry.
   */
  private static String capReason(String reason) {
    return reason == null || reason.length() <= AuditQueryService.MAX_REASON_LENGTH
        ? reason
        : reason.substring(0, AuditQueryService.MAX_REASON_LENGTH);
  }

  /**
   * {@link AuditEvent} copies {@code after} via {@link Map#copyOf}, which rejects {@code null}
   * values - an omitted optional request field is simply absent from the event.
   */
  private static Map<String, Object> withoutNullValues(Map<String, Object> values) {
    Map<String, Object> copy = new LinkedHashMap<>();
    values.forEach(
        (key, value) -> {
          if (value != null) {
            copy.put(key, value);
          }
        });
    return copy;
  }

  /**
   * The pipeline has no row of its own; a name-derived UUID identifies it the way {@code
   * AuditRetentionSettingsService} identifies a settings object without one. An absent pipelineId
   * still yields a stable object id, so the rejected call can be recorded.
   */
  private static UUID pipelineObjectId(String pipelineId) {
    return UUID.nameUUIDFromBytes(String.valueOf(pipelineId).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Checked before the audited section: without a library there is no object to record the call
   * against - the request is malformed rather than an administrative decision.
   */
  private static UUID requireLibraryId(UUID libraryId) {
    if (libraryId == null) {
      throw new IllegalArgumentException("libraryId ist erforderlich");
    }
    return libraryId;
  }

  /**
   * The batch size the call is about - defaulted but not yet validated, so a rejected out-of-range
   * value is recorded as requested.
   */
  private static int effectiveBatchSize(Integer requestedBatchSize) {
    return requestedBatchSize == null ? DEFAULT_REINDEX_BATCH_SIZE : requestedBatchSize;
  }

  private static int requireBatchSize(int batchSize) {
    if (batchSize < 1 || batchSize > MAX_REINDEX_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "batchSize muss zwischen 1 und " + MAX_REINDEX_BATCH_SIZE + " liegen, war " + batchSize);
    }
    return batchSize;
  }
}
