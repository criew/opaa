package io.opaa.externalaccess.web;

import io.opaa.api.dto.CreateServiceAccountTokenRequest;
import io.opaa.api.dto.CreatedServiceAccountTokenResponse;
import io.opaa.api.dto.ServiceAccountDetailResponse;
import io.opaa.api.dto.ServiceAccountListResponse;
import io.opaa.api.dto.ServiceAccountRequest;
import io.opaa.api.dto.ServiceAccountResponse;
import io.opaa.api.dto.ServiceAccountTokenListResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.RequiresRecentLogin;
import io.opaa.common.ConditionalOnFeature;
import io.opaa.common.Feature;
import io.opaa.externalaccess.token.ServiceAccountService;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service accounts and their tokens, {@code SYSTEM_ADMIN} only (ADR-0035, Entscheidung 7). A token
 * has no library selection and so no update path; what it sees is the consent of the library
 * administration, managed on the library ({@link ServiceAccountConsentController}).
 */
@ConditionalOnFeature(Feature.EXTERNAL_ACCESS)
@RestController
@RequestMapping("/api/v1/admin/service-accounts")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class ServiceAccountAdminController {

  private final ServiceAccountService service;
  private final Clock clock;

  public ServiceAccountAdminController(ServiceAccountService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  @GetMapping
  public ServiceAccountListResponse listServiceAccounts(@Caller CurrentUser caller) {
    return new ServiceAccountListResponse(
        service.list(caller.organizationId()).stream()
            .map(ServiceAccountResponseMapper::toResponse)
            .toList());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ServiceAccountResponse createServiceAccount(
      @Valid @RequestBody ServiceAccountRequest request, @Caller CurrentUser caller) {
    return ServiceAccountResponseMapper.toResponse(
        service.create(
            caller.id(),
            caller.organizationId(),
            request.getName(),
            request.getDescription(),
            request.getResponsibleUserId(),
            request.getRateLimitPerHour()));
  }

  @GetMapping("/{serviceAccountId}")
  public ServiceAccountDetailResponse getServiceAccount(
      @PathVariable UUID serviceAccountId, @Caller CurrentUser caller) {
    return ServiceAccountResponseMapper.toDetail(
        service.detail(caller.organizationId(), serviceAccountId), clock.instant());
  }

  @PutMapping("/{serviceAccountId}")
  public ServiceAccountResponse updateServiceAccount(
      @PathVariable UUID serviceAccountId,
      @Valid @RequestBody ServiceAccountRequest request,
      @Caller CurrentUser caller) {
    return ServiceAccountResponseMapper.toResponse(
        service.update(
            caller.id(),
            caller.organizationId(),
            serviceAccountId,
            request.getName(),
            request.getDescription(),
            request.getResponsibleUserId(),
            request.getRateLimitPerHour()));
  }

  @PostMapping("/{serviceAccountId}/deactivate")
  public ServiceAccountResponse deactivateServiceAccount(
      @PathVariable UUID serviceAccountId, @Caller CurrentUser caller) {
    return ServiceAccountResponseMapper.toResponse(
        service.deactivate(caller.id(), caller.organizationId(), serviceAccountId));
  }

  @GetMapping("/{serviceAccountId}/tokens")
  public ServiceAccountTokenListResponse listServiceAccountTokens(
      @PathVariable UUID serviceAccountId, @Caller CurrentUser caller) {
    Instant now = clock.instant();
    return new ServiceAccountTokenListResponse(
        service.listTokens(caller.organizationId(), serviceAccountId).stream()
            .map(token -> ServiceAccountResponseMapper.toToken(token, now))
            .toList());
  }

  /**
   * Requires a recent sign-in, as the issuance of a personal token does: the token outlives the
   * session it was issued from.
   */
  @PostMapping("/{serviceAccountId}/tokens")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresRecentLogin
  public CreatedServiceAccountTokenResponse createServiceAccountToken(
      @PathVariable UUID serviceAccountId,
      @Valid @RequestBody CreateServiceAccountTokenRequest request,
      @Caller CurrentUser caller) {
    return ServiceAccountResponseMapper.toCreated(
        service.issueToken(
            caller.id(),
            caller.organizationId(),
            serviceAccountId,
            request.getName(),
            request.getExpiresAt()),
        clock.instant());
  }

  @DeleteMapping("/{serviceAccountId}/tokens/{tokenId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeServiceAccountToken(
      @PathVariable UUID serviceAccountId, @PathVariable UUID tokenId, @Caller CurrentUser caller) {
    service.revokeToken(caller.id(), caller.organizationId(), serviceAccountId, tokenId);
  }
}
