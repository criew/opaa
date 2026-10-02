package io.opaa.directory.web;

import io.opaa.api.dto.DirectorySyncPendingPlanResponse;
import io.opaa.api.dto.DirectorySyncPlanDecisionRequest;
import io.opaa.api.dto.DirectorySyncReportResponse;
import io.opaa.api.dto.DirectorySyncStatusResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.directory.sync.DirectorySyncReportDisclosure;
import io.opaa.directory.sync.DirectorySyncReportDisclosure.Channel;
import io.opaa.directory.sync.DirectorySyncService;
import io.opaa.directory.sync.PendingPlanView;
import io.opaa.directory.sync.SyncReport;
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
 *
 * <p>Every response carrying a report records its disclosure before it is returned ({@link
 * DirectorySyncReportDisclosure}): the report names the persons it adds, removes and locks.
 */
@RestController
public class DirectorySyncController {

  private final DirectorySyncService directorySyncService;
  private final DirectorySyncReportDisclosure reportDisclosure;

  public DirectorySyncController(
      DirectorySyncService directorySyncService, DirectorySyncReportDisclosure reportDisclosure) {
    this.directorySyncService = directorySyncService;
    this.reportDisclosure = reportDisclosure;
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
    return disclose(caller, providerId, Channel.DRY_RUN, report);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/api/v1/admin/oidc-providers/{providerId}/directory-sync/run")
  public DirectorySyncReportResponse run(
      @PathVariable UUID providerId, @Caller CurrentUser caller) {
    SyncReport report = directorySyncService.run(caller.organizationId(), providerId);
    return disclose(caller, providerId, Channel.RUN, report);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/api/v1/admin/oidc-providers/{providerId}/directory-sync/pending-plan")
  public DirectorySyncPendingPlanResponse getPendingPlan(
      @PathVariable UUID providerId, @Caller CurrentUser caller) {
    PendingPlanView plan =
        directorySyncService
            .getPendingPlan(caller.organizationId(), providerId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "Für diesen Anbieter liegt kein Plan zur Entscheidung vor."));
    reportDisclosure.recordIfNamed(
        caller.organizationId(),
        caller.id(),
        providerId,
        Channel.PENDING_PLAN,
        plan.id(),
        plan.report());
    return DirectorySyncResponseMapper.toPendingPlanResponse(plan);
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
    return disclose(caller, providerId, Channel.PLAN_CONFIRMATION, report);
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

  private DirectorySyncReportResponse disclose(
      CurrentUser caller, UUID providerId, Channel channel, SyncReport report) {
    reportDisclosure.recordIfNamed(
        caller.organizationId(), caller.id(), providerId, channel, null, report);
    return DirectorySyncResponseMapper.toReportResponse(report);
  }
}
