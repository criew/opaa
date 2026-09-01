package io.opaa.api;

import io.opaa.api.dto.DiagnosticContextRetentionRequest;
import io.opaa.api.dto.DiagnosticContextRetentionResponse;
import io.opaa.api.dto.DiagnosticImpersonationGrantListResponse;
import io.opaa.api.dto.DiagnosticImpersonationGrantRequest;
import io.opaa.api.dto.DiagnosticImpersonationGrantResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.diagnosticaccess.DiagnosticContextRetentionService;
import io.opaa.diagnosticaccess.DiagnosticImpersonationGrantCreation;
import io.opaa.diagnosticaccess.DiagnosticImpersonationGrantService;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration of the "Sicht als" befugnis and of the protocol's retention period.
 *
 * <p>Deliberately no {@code @PreAuthorize} here: the system-admin check lives in the services, so
 * one place decides it for every caller (HTTP or otherwise) and a future second call site cannot
 * bypass it by not being a controller method.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class DiagnosticImpersonationGrantController {

  private final DiagnosticImpersonationGrantService grantService;
  private final DiagnosticContextRetentionService retentionService;
  private final Clock clock;

  public DiagnosticImpersonationGrantController(
      DiagnosticImpersonationGrantService grantService,
      DiagnosticContextRetentionService retentionService,
      Clock clock) {
    this.grantService = grantService;
    this.retentionService = retentionService;
    this.clock = clock;
  }

  @GetMapping("/diagnostic-impersonation-grants")
  public DiagnosticImpersonationGrantListResponse listDiagnosticImpersonationGrants(
      @Caller CurrentUser caller) {
    return DiagnosticAccessResponseMapper.toListResponse(
        grantService.list(caller), clock.instant());
  }

  @PostMapping("/diagnostic-impersonation-grants")
  public ResponseEntity<DiagnosticImpersonationGrantResponse> grantDiagnosticImpersonation(
      @Valid @RequestBody DiagnosticImpersonationGrantRequest request, @Caller CurrentUser caller) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            DiagnosticAccessResponseMapper.toResponse(
                grantService.grant(
                    caller,
                    new DiagnosticImpersonationGrantCreation(
                        request.getHolderUserId(),
                        request.getScopeGroupId(),
                        request.getValidFrom(),
                        request.getValidUntil())),
                clock.instant()));
  }

  @DeleteMapping("/diagnostic-impersonation-grants/{grantId}")
  public DiagnosticImpersonationGrantResponse revokeDiagnosticImpersonation(
      @PathVariable UUID grantId, @Caller CurrentUser caller) {
    return DiagnosticAccessResponseMapper.toResponse(
        grantService.revoke(caller, grantId), clock.instant());
  }

  @GetMapping("/diagnostic-context/retention")
  public DiagnosticContextRetentionResponse getDiagnosticContextRetention(
      @Caller CurrentUser caller) {
    return DiagnosticAccessResponseMapper.toResponse(retentionService.read(caller));
  }

  @PutMapping("/diagnostic-context/retention")
  public DiagnosticContextRetentionResponse updateDiagnosticContextRetention(
      @Valid @RequestBody DiagnosticContextRetentionRequest request, @Caller CurrentUser caller) {
    return DiagnosticAccessResponseMapper.toResponse(
        retentionService.updateRetentionMonths(caller, request.getRetentionMonths()));
  }
}
