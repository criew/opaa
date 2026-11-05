package io.opaa.externalaccess.web;

import io.opaa.api.dto.LibraryServiceAccountConsentListResponse;
import io.opaa.api.dto.LibraryServiceAccountConsentRequest;
import io.opaa.api.dto.LibraryServiceAccountConsentResponse;
import io.opaa.api.dto.ServiceAccountChoiceListResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConditionalOnFeature;
import io.opaa.common.Feature;
import io.opaa.externalaccess.token.ServiceAccountConsentService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Fachverfahren mit Zugriff" of one library (ADR-0035, Entscheidung 7): the consents of its
 * administration to named service accounts. Who may is decided per library in {@link
 * ServiceAccountConsentService}, not by a system role.
 */
@ConditionalOnFeature(Feature.EXTERNAL_ACCESS)
@RestController
@RequestMapping("/api/v1/libraries/{libraryId}")
public class ServiceAccountConsentController {

  private final ServiceAccountConsentService service;

  public ServiceAccountConsentController(ServiceAccountConsentService service) {
    this.service = service;
  }

  @GetMapping("/service-accounts")
  public LibraryServiceAccountConsentListResponse listLibraryServiceAccountConsents(
      @PathVariable UUID libraryId, @Caller CurrentUser caller) {
    return new LibraryServiceAccountConsentListResponse(
        service.list(caller, libraryId).stream()
            .map(ServiceAccountResponseMapper::toLibraryConsent)
            .toList(),
        service.maxConsentDays());
  }

  @PutMapping("/service-accounts/{serviceAccountId}")
  public LibraryServiceAccountConsentResponse setLibraryServiceAccountConsent(
      @PathVariable UUID libraryId,
      @PathVariable UUID serviceAccountId,
      @Valid @RequestBody LibraryServiceAccountConsentRequest request,
      @Caller CurrentUser caller) {
    return ServiceAccountResponseMapper.toLibraryConsent(
        service.set(caller, libraryId, serviceAccountId, request.getExpiresAt()));
  }

  @DeleteMapping("/service-accounts/{serviceAccountId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeLibraryServiceAccountConsent(
      @PathVariable UUID libraryId,
      @PathVariable UUID serviceAccountId,
      @Caller CurrentUser caller) {
    service.revoke(caller, libraryId, serviceAccountId);
  }

  @GetMapping("/service-account-choices")
  public ServiceAccountChoiceListResponse listServiceAccountChoices(
      @PathVariable UUID libraryId, @Caller CurrentUser caller) {
    return new ServiceAccountChoiceListResponse(
        service.choices(caller, libraryId).stream()
            .map(ServiceAccountResponseMapper::toChoice)
            .toList());
  }
}
