package io.opaa.externalaccess.web;

import io.opaa.api.dto.CreatedServiceAccountTokenResponse;
import io.opaa.api.dto.LibraryServiceAccountConsentResponse;
import io.opaa.api.dto.ServiceAccountChoiceResponse;
import io.opaa.api.dto.ServiceAccountConsentStatus;
import io.opaa.api.dto.ServiceAccountConsentedLibraryResponse;
import io.opaa.api.dto.ServiceAccountDetailResponse;
import io.opaa.api.dto.ServiceAccountResponse;
import io.opaa.api.dto.ServiceAccountTokenResponse;
import io.opaa.externalaccess.token.ServiceAccount;
import io.opaa.externalaccess.token.ServiceAccountConsentService.LibraryConsent;
import io.opaa.externalaccess.token.ServiceAccountService.ConsentedLibrary;
import io.opaa.externalaccess.token.ServiceAccountService.IssuedServiceAccountToken;
import io.opaa.externalaccess.token.ServiceAccountService.ServiceAccountDetail;
import io.opaa.externalaccess.token.ServiceAccountService.ServiceAccountOverview;
import io.opaa.externalaccess.token.ServiceAccountToken;
import java.time.Instant;

/**
 * Maps service accounts, their tokens and the consents of libraries onto the generated responses
 * (ADR-0006). No response carries a usage date, and only {@link #toCreated} the token value.
 */
final class ServiceAccountResponseMapper {

  private ServiceAccountResponseMapper() {}

  static ServiceAccountResponse toResponse(ServiceAccountOverview view) {
    ServiceAccount account = view.account();
    ServiceAccountResponse response =
        new ServiceAccountResponse(
            account.getId(),
            account.getName(),
            account.isActive(),
            view.responsiblePersonMissing(),
            view.effectiveRateLimitPerHour(),
            view.activeTokenCount(),
            view.consentedLibraryCount(),
            account.getCreatedAt());
    response.setDescription(account.getDescription());
    response.setDeactivatedAt(account.getDeactivatedAt());
    response.setResponsibleUserId(account.getResponsibleUserId());
    response.setResponsibleDisplayName(view.responsibleDisplayName());
    response.setRateLimitPerHour(account.getRateLimitPerHour());
    response.setNextExpiryAt(view.nextExpiryAt());
    return response;
  }

  static ServiceAccountDetailResponse toDetail(ServiceAccountDetail detail, Instant now) {
    return new ServiceAccountDetailResponse(
        toResponse(detail.overview()),
        detail.tokens().stream().map(token -> toToken(token, now)).toList(),
        detail.libraries().stream().map(ServiceAccountResponseMapper::toConsented).toList());
  }

  static ServiceAccountTokenResponse toToken(ServiceAccountToken token, Instant now) {
    return new ServiceAccountTokenResponse(
        token.getId(),
        token.getName(),
        token.getTokenPrefix(),
        token.getCreatedAt(),
        token.getExpiresAt(),
        token.status(now));
  }

  static CreatedServiceAccountTokenResponse toCreated(
      IssuedServiceAccountToken issued, Instant now) {
    ServiceAccountToken token = issued.token();
    return new CreatedServiceAccountTokenResponse(
        token.getId(),
        token.getName(),
        token.getTokenPrefix(),
        issued.rawValue(),
        token.getCreatedAt(),
        token.getExpiresAt(),
        token.status(now));
  }

  static LibraryServiceAccountConsentResponse toLibraryConsent(LibraryConsent consent) {
    ServiceAccount account = consent.account();
    LibraryServiceAccountConsentResponse response =
        new LibraryServiceAccountConsentResponse(
            account.getId(),
            account.getName(),
            account.isActive(),
            consent.consent().getGrantedAt(),
            consent.consent().getExpiresAt(),
            status(consent.inForce()));
    response.setServiceAccountDescription(account.getDescription());
    return response;
  }

  static ServiceAccountChoiceResponse toChoice(ServiceAccount account) {
    ServiceAccountChoiceResponse response =
        new ServiceAccountChoiceResponse(account.getId(), account.getName());
    response.setDescription(account.getDescription());
    return response;
  }

  private static ServiceAccountConsentedLibraryResponse toConsented(ConsentedLibrary library) {
    return new ServiceAccountConsentedLibraryResponse(
        library.consent().getLibraryId(),
        library.libraryName(),
        library.consent().getGrantedAt(),
        library.consent().getExpiresAt(),
        status(library.inForce()));
  }

  private static ServiceAccountConsentStatus status(boolean inForce) {
    return inForce ? ServiceAccountConsentStatus.ACTIVE : ServiceAccountConsentStatus.EXPIRED;
  }
}
