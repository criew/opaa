package io.opaa.api;

import io.opaa.api.dto.CapabilityGrantRequest;
import io.opaa.api.dto.CapabilityGrantResponse;
import io.opaa.api.dto.CapabilityOverviewResponse;
import io.opaa.api.types.Capability;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.permission.CapabilityGrant;
import io.opaa.permission.CapabilityGrantView;
import io.opaa.permission.CapabilityService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration of the installation-wide capabilities (ADR-0036, Entscheidung 5). System-admin
 * only throughout: holding a capability is not the same as being allowed to hand it out, and
 * "Verwalten ist nicht Lesen" stays in force - nothing here opens any content.
 */
@RestController
@RequestMapping("/api/v1/admin/capabilities")
public class CapabilityController {

  private final CapabilityService capabilityService;

  public CapabilityController(CapabilityService capabilityService) {
    this.capabilityService = capabilityService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public List<CapabilityOverviewResponse> listCapabilities(@Caller CurrentUser caller) {
    return CapabilityResponseMapper.toOverviewResponses(
        capabilityService.overview(caller.organizationId()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{capability}/grants")
  public ResponseEntity<CapabilityGrantResponse> grantCapability(
      @PathVariable Capability capability,
      @Valid @RequestBody CapabilityGrantRequest request,
      @Caller CurrentUser caller) {
    CapabilityGrant grant =
        capabilityService.grant(
            capability, request.getSubjectType(), request.getSubjectId(), caller);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            CapabilityResponseMapper.toResponse(
                new CapabilityGrantView(grant, capabilityService.subjectName(grant))));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/{capability}/grants/{grantId}")
  public ResponseEntity<Void> revokeCapability(
      @PathVariable Capability capability, @PathVariable UUID grantId, @Caller CurrentUser caller) {
    capabilityService.revoke(capability, grantId, caller);
    return ResponseEntity.noContent().build();
  }
}
