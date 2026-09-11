package io.opaa.api;

import io.opaa.api.dto.OrphanedLibraryDeletionRequest;
import io.opaa.api.dto.OrphanedLibraryReportRequest;
import io.opaa.api.dto.OrphanedLibraryReportResponse;
import io.opaa.api.dto.OrphanedOriginalDeletionRequest;
import io.opaa.api.dto.OrphanedOriginalDeletionResponse;
import io.opaa.api.dto.OrphanedOriginalReportRequest;
import io.opaa.api.dto.OrphanedOriginalReportResponse;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.library.OrphanedOriginalCleanupService;
import io.opaa.library.OrphanedOriginalDeletion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only maintenance of the storage of uploaded originals, {@code SYSTEM_ADMIN} only - the same
 * access bar {@link IndexingAdminController} establishes, and the same organization scoping: the
 * library comes from the request, the organization always from the caller.
 *
 * <p>The cleanup of orphaned originals is two calls, never one (ADR-0030 "Konsequenzen"): reporting
 * changes nothing and is not audited, deleting removes only the locators it is handed and leaves
 * exactly one audit event per call, executed or rejected. That holds for both runs - the one inside
 * a library and the one over the organization's whole storage area, which reaches the originals of
 * a library that was deleted before they were.
 */
@RestController
@RequestMapping("/api/v1/admin/upload-store")
public class UploadStoreAdminController {

  private final OrphanedOriginalCleanupService cleanupService;
  private final AuditedAdminCall auditedAdminCall;

  public UploadStoreAdminController(
      OrphanedOriginalCleanupService cleanupService, AuditEventRecorder auditEventRecorder) {
    this.cleanupService = cleanupService;
    this.auditedAdminCall = new AuditedAdminCall(auditEventRecorder);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/orphan-originals/report")
  public OrphanedOriginalReportResponse reportOrphanedOriginals(
      @RequestBody OrphanedOriginalReportRequest request, @Caller CurrentUser caller) {
    UUID libraryId = requireLibraryId(request.getLibraryId());
    return OrphanedOriginalResponseMapper.toReportResponse(
        cleanupService.report(caller.organizationId(), libraryId, request.getMinimumAgeMinutes()));
  }

  /**
   * Removes the named originals. The audit event carries every locator that actually went - the one
   * record of what this call removed, bounded by the cap on {@code locators} itself.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/orphan-originals/delete")
  public OrphanedOriginalDeletionResponse deleteOrphanedOriginals(
      @RequestBody OrphanedOriginalDeletionRequest request, @Caller CurrentUser caller) {
    UUID libraryId = requireLibraryId(request.getLibraryId());
    List<String> locators = request.getLocators() == null ? List.of() : request.getLocators();
    Map<String, Object> requested = new LinkedHashMap<>();
    requested.put("requestedCount", locators.size());
    OrphanedOriginalDeletion deletion =
        auditedAdminCall.run(
            caller,
            AuditEventType.UPLOAD_ORPHAN_ORIGINALS_DELETED,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            libraryId,
            "Bibliothek " + libraryId,
            requested,
            () -> cleanupService.delete(caller.organizationId(), libraryId, locators),
            outcome ->
                Map.of(
                    "deletedCount", outcome.deleted().size(),
                    "skippedCount", outcome.skipped().size(),
                    "deleted", outcome.deleted()));
    return OrphanedOriginalResponseMapper.toDeletionResponse(deletion);
  }

  /**
   * The storage-bound report needs no library: it walks the caller's own organization and names the
   * storage areas whose library row is gone. Like the library-bound report it changes nothing and
   * leaves no audit event.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/orphan-libraries/report")
  public OrphanedLibraryReportResponse reportOrphanedLibraries(
      @RequestBody OrphanedLibraryReportRequest request, @Caller CurrentUser caller) {
    return OrphanedOriginalResponseMapper.toLibraryReportResponse(
        cleanupService.reportOrphanedLibraries(
            caller.organizationId(), request.getMinimumAgeMinutes()));
  }

  /**
   * Removes the named originals from the storage area of a library that no longer exists. Audited
   * exactly like the library-bound deletion, under the same event type and against the same object
   * - the library id the storage area carries is all that identifies it.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/orphan-libraries/delete")
  public OrphanedOriginalDeletionResponse deleteOrphanedLibraryOriginals(
      @RequestBody OrphanedLibraryDeletionRequest request, @Caller CurrentUser caller) {
    UUID libraryId = requireLibraryId(request.getLibraryId());
    List<String> locators = request.getLocators() == null ? List.of() : request.getLocators();
    Map<String, Object> requested = new LinkedHashMap<>();
    requested.put("requestedCount", locators.size());
    OrphanedOriginalDeletion deletion =
        auditedAdminCall.run(
            caller,
            AuditEventType.UPLOAD_ORPHAN_ORIGINALS_DELETED,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            libraryId,
            "Gelöschte Bibliothek " + libraryId,
            requested,
            () ->
                cleanupService.deleteInOrphanedLibrary(
                    caller.organizationId(), libraryId, locators),
            outcome ->
                Map.of(
                    "deletedCount", outcome.deleted().size(),
                    "skippedCount", outcome.skipped().size(),
                    "deleted", outcome.deleted()));
    return OrphanedOriginalResponseMapper.toDeletionResponse(deletion);
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
}
