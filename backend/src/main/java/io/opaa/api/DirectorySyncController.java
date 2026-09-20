package io.opaa.api;

import io.opaa.api.dto.DirectorySyncPendingPlanResponse;
import io.opaa.api.dto.DirectorySyncPlanDecisionRequest;
import io.opaa.api.dto.DirectorySyncReportResponse;
import io.opaa.api.dto.DirectorySyncStatusResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.group.sync.DirectorySyncService;
import io.opaa.group.sync.SyncReport;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The directory run's own operations, {@code SYSTEM_ADMIN} only. Everything but the overview hangs
 * under the provider it belongs to: since #1816 a run is bound to one identity provider (ADR-0036,
 * Entscheidung 2), so there is no organization-wide run to address any more.
 */
@RestController
public class DirectorySyncController {

  private final DirectorySyncService directorySyncService;

  public DirectorySyncController(DirectorySyncService directorySyncService) {
    this.directorySyncService = directorySyncService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/api/v1/admin/directory-sync/status")
  public List<DirectorySyncStatusResponse> listStatus(@Caller CurrentUser caller) {
    return DirectorySyncResponseMapper.toStatusResponses(
        directorySyncService.listStatus(caller.organizationId()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/api/v1/admin/oidc-providers/{providerId}/directory-sync/dry-run")
  public DirectorySyncReportResponse dryRun(
      @PathVariable UUID providerId, @Caller CurrentUser caller) {
    SyncReport report = directorySyncService.dryRun(caller.organizationId(), providerId);
    return DirectorySyncResponseMapper.toReportResponse(report);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/api/v1/admin/oidc-providers/{providerId}/directory-sync/run")
  public DirectorySyncReportResponse run(
      @PathVariable UUID providerId, @Caller CurrentUser caller) {
    SyncReport report = directorySyncService.run(caller.organizationId(), providerId);
    return DirectorySyncResponseMapper.toReportResponse(report);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/api/v1/admin/oidc-providers/{providerId}/directory-sync/pending-plan")
  public DirectorySyncPendingPlanResponse getPendingPlan(
      @PathVariable UUID providerId, @Caller CurrentUser caller) {
    return directorySyncService
        .getPendingPlan(caller.organizationId(), providerId)
        .map(DirectorySyncResponseMapper::toPendingPlanResponse)
        .orElseThrow(
            () ->
                new NotFoundException("Für diesen Anbieter liegt kein Plan zur Entscheidung vor."));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping(
      "/api/v1/admin/oidc-providers/{providerId}/directory-sync/pending-plan/{planId}/confirm")
  public DirectorySyncReportResponse confirmPlan(
      @PathVariable UUID providerId,
      @PathVariable UUID planId,
      @Valid @RequestBody DirectorySyncPlanDecisionRequest request,
      @Caller CurrentUser caller) {
    SyncReport report =
        directorySyncService.confirmPlan(
            caller.organizationId(), providerId, planId, caller.id(), request.getReason());
    return DirectorySyncResponseMapper.toReportResponse(report);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping(
      "/api/v1/admin/oidc-providers/{providerId}/directory-sync/pending-plan/{planId}/discard")
  public ResponseEntity<Void> discardPlan(
      @PathVariable UUID providerId,
      @PathVariable UUID planId,
      @Valid @RequestBody DirectorySyncPlanDecisionRequest request,
      @Caller CurrentUser caller) {
    directorySyncService.discardPlan(
        caller.organizationId(), providerId, planId, caller.id(), request.getReason());
    return ResponseEntity.noContent().build();
  }
}
