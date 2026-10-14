package io.opaa.indexing.web;

import io.opaa.api.dto.ContextPrefixRerunRequest;
import io.opaa.api.dto.ContextPrefixRerunResponse;
import io.opaa.api.dto.MetadataBackfillRequest;
import io.opaa.api.dto.MetadataBackfillResponse;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.web.AuditedAdminCall;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConditionalOnFeature;
import io.opaa.common.Feature;
import io.opaa.common.ValidationException;
import io.opaa.indexing.maintenance.ContextPrefixRerunResult;
import io.opaa.indexing.maintenance.ContextPrefixRerunService;
import io.opaa.indexing.maintenance.MetadataBackfillResult;
import io.opaa.indexing.maintenance.MetadataBackfillService;
import io.opaa.metadata.CoreMetadataExtractor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two metadata runs of the indexing administration, {@code SYSTEM_ADMIN} only and scoped to the
 * caller's own organization like every endpoint of {@link IndexingAdminController}. Absent while
 * the metadata feature is off (ADR-0044), so neither run can be started then.
 */
@RestController
@ConditionalOnFeature(Feature.METADATA)
@RequestMapping("/api/v1/admin/indexing")
public class MetadataMaintenanceAdminController {

  private final MetadataBackfillService metadataBackfillService;
  private final ContextPrefixRerunService contextPrefixRerunService;
  private final AuditedAdminCall auditedAdminCall;

  public MetadataMaintenanceAdminController(
      MetadataBackfillService metadataBackfillService,
      ContextPrefixRerunService contextPrefixRerunService,
      AuditEventRecorder auditEventRecorder) {
    this.metadataBackfillService = metadataBackfillService;
    this.contextPrefixRerunService = contextPrefixRerunService;
    this.auditedAdminCall = new AuditedAdminCall(auditEventRecorder);
  }

  /**
   * One batch of the deterministic core-metadata backfill. The library is the explicit,
   * library-wise release the specification demands; a library of another organization is absent
   * (404), decided in the service. Pausing is not calling again; the next call resumes.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/metadata-backfill")
  public MetadataBackfillResponse backfillMetadataBatch(
      @RequestBody MetadataBackfillRequest request, @Caller CurrentUser caller) {
    UUID libraryId = requireLibraryId(request.getLibraryId());
    int batchSize = IndexingAdminController.effectiveBatchSize(request.getBatchSize());
    Map<String, Object> requested = new LinkedHashMap<>();
    requested.put("extractionVersion", CoreMetadataExtractor.EXTRACTION_VERSION);
    requested.put("batchSize", batchSize);
    MetadataBackfillResult result =
        auditedAdminCall.run(
            caller,
            AuditEventType.INDEXING_METADATA_BACKFILL_TRIGGERED,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            libraryId,
            "Bibliothek " + libraryId,
            requested,
            () ->
                metadataBackfillService.backfillBatch(
                    caller.organizationId(),
                    libraryId,
                    IndexingAdminController.requireBatchSize(batchSize)),
            outcome ->
                Map.of(
                    "processedDocuments", outcome.processedDocuments(),
                    "markedForNextRun", outcome.markedForNextRun(),
                    "skippedDocuments", outcome.skippedDocuments()));
    return MetadataBackfillResponseMapper.toBackfillResponse(result);
  }

  /**
   * One batch of the Kontextpräfix-Nachlauf. The library is the explicit, library-wise release the
   * specification demands; saving a schema change never starts it. Pausing is not calling again;
   * the next call resumes at the next unprocessed document.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/context-prefix-rerun")
  public ContextPrefixRerunResponse rerunContextPrefixBatch(
      @RequestBody ContextPrefixRerunRequest request, @Caller CurrentUser caller) {
    UUID libraryId = requireLibraryId(request.getLibraryId());
    int batchSize = IndexingAdminController.effectiveBatchSize(request.getBatchSize());
    Map<String, Object> requested = new LinkedHashMap<>();
    requested.put("batchSize", batchSize);
    ContextPrefixRerunResult result =
        auditedAdminCall.run(
            caller,
            AuditEventType.INDEXING_CONTEXT_PREFIX_RERUN_TRIGGERED,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            libraryId,
            "Bibliothek " + libraryId,
            requested,
            () ->
                contextPrefixRerunService.rerunBatch(
                    caller.organizationId(),
                    libraryId,
                    IndexingAdminController.requireBatchSize(batchSize)),
            outcome ->
                Map.of(
                    "processedDocuments", outcome.processedDocuments(),
                    "skippedDocuments", outcome.skippedDocuments()));
    return MetadataBackfillResponseMapper.toRerunResponse(result);
  }

  /**
   * Checked before the audited section: without a library there is no object to record the call
   * against - the request is malformed rather than an administrative decision.
   */
  private static UUID requireLibraryId(UUID libraryId) {
    if (libraryId == null) {
      throw new ValidationException("libraryId ist erforderlich");
    }
    return libraryId;
  }
}
