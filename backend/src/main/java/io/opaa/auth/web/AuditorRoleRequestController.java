package io.opaa.auth.web;

import io.opaa.api.dto.AuditorRoleConfirmationResponse;
import io.opaa.api.dto.AuditorRoleRequestResponse;
import io.opaa.auth.AuditorRoles;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.UserService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The open requests for the AUDITOR role, decided by the revision alone: the system administration
 * or a provider's roles claim asks, an auditor confirms or declines. The service checks the stored
 * role once more, so a role withdrawn since the token was issued decides nothing. A confirmation
 * answers the link that sets the new password of a local account - to the auditor only.
 */
@RestController
@RequestMapping("/api/v1/audit/auditor-role-requests")
@PreAuthorize("hasRole('AUDITOR')")
public class AuditorRoleRequestController {

  private final AuditorRoles auditorRoles;
  private final UserService userService;

  public AuditorRoleRequestController(AuditorRoles auditorRoles, UserService userService) {
    this.auditorRoles = auditorRoles;
    this.userService = userService;
  }

  @GetMapping
  public List<AuditorRoleRequestResponse> listAuditorRoleRequests(@Caller CurrentUser caller) {
    return auditorRoles.openRequests(caller.organizationId()).stream()
        .map(AuditorRoleRequestController::toResponse)
        .toList();
  }

  @PostMapping("/{requestId}/confirm")
  public AuditorRoleConfirmationResponse confirmAuditorRoleRequest(
      @PathVariable UUID requestId, @Caller CurrentUser caller) {
    AuditorRoleConfirmationResponse response = new AuditorRoleConfirmationResponse();
    response.setSetupUrl(userService.confirmAuditorRequest(requestId, caller).setupLink());
    return response;
  }

  @PostMapping("/{requestId}/decline")
  public ResponseEntity<Void> declineAuditorRoleRequest(
      @PathVariable UUID requestId, @Caller CurrentUser caller) {
    userService.declineAuditorRequest(requestId, caller);
    return ResponseEntity.noContent().build();
  }

  private static AuditorRoleRequestResponse toResponse(AuditorRoles.OpenRequest open) {
    AuditorRoleRequestResponse response = new AuditorRoleRequestResponse();
    response.setId(open.request().getId());
    response.setUserId(open.user().getId());
    response.setDisplayName(open.user().getDisplayName());
    response.setEmail(open.user().getEmail());
    response.setLocalAccount(open.localAccount());
    response.setRequestedByDisplayName(open.requestedBy());
    response.setRequestedByProvider(open.request().isFromProvider());
    response.setRequestedAt(open.request().getRequestedAt());
    response.setEmailChangedByAdministrationAt(open.emailChangedByAdministrationAt());
    return response;
  }
}
