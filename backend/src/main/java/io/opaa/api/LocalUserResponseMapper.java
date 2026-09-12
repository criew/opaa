package io.opaa.api;

import io.opaa.api.dto.LocalAuthSettingsResponse;
import io.opaa.api.dto.LocalUserCreatedResponse;
import io.opaa.api.dto.LocalUserCreationMode;
import io.opaa.api.dto.LocalUserPageResponse;
import io.opaa.api.dto.LocalUserPasswordResetResponse;
import io.opaa.api.dto.LocalUserResponse;
import io.opaa.api.dto.LocalUserSummaryResponse;
import io.opaa.auth.local.LinkDelivery;
import io.opaa.auth.local.LocalAuthSettings;
import io.opaa.auth.local.LocalAuthSettingsService;
import io.opaa.auth.local.LocalCredentials;
import io.opaa.auth.local.LocalUserCreated;
import io.opaa.auth.local.LocalUserOverview;
import io.opaa.auth.local.LocalUserPage;
import io.opaa.auth.local.LocalUserSummary;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Maps the local account administration onto the generated DTOs (ADR-0006). A row carries the
 * activity as a class and never the failed-login counter - the DTOs have no field for either
 * (ADR-0033, Entscheidung 11).
 */
final class LocalUserResponseMapper {

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);

  private LocalUserResponseMapper() {}

  static LocalUserResponse toResponse(LocalUserOverview overview) {
    LocalCredentials row = overview.credentials();
    LocalUserResponse response =
        new LocalUserResponse(
            overview.user().getId(),
            overview.user().getEmail(),
            overview.user().getDisplayName(),
            overview.user().getSystemRole(),
            overview.state(),
            row.isPasswordChangeRequired(),
            row.getCreatedReason(),
            row.getCreatedAt(),
            overview.activity(),
            row.isBootstrap());
    response.setLockedReason(row.getLockedReason());
    response.setPasswordChangeReason(row.getPasswordChangeReason());
    response.setExpiresAt(row.getExpiresAt());
    return response;
  }

  static LocalUserPageResponse toPage(LocalUserPage page) {
    return new LocalUserPageResponse(
        page.items().stream().map(LocalUserResponseMapper::toResponse).toList(),
        page.total(),
        page.page(),
        page.size());
  }

  static LocalUserSummaryResponse toSummary(LocalUserSummary summary) {
    LocalUserSummaryResponse response =
        new LocalUserSummaryResponse(
            summary.total(), summary.withoutExpiry(), summary.locked(), summary.invitedPending());
    response.setLastReviewHint(
        "Die nächste Wiedervorlage zur Prüfung der lokalen Konten geht am "
            + DATE.format(summary.nextReviewOn())
            + " an die Systemverwaltung.");
    return response;
  }

  static LocalUserCreatedResponse toCreated(LocalUserCreated created, LocalUserCreationMode mode) {
    LinkDelivery delivery = created.delivery();
    LocalUserCreatedResponse response =
        new LocalUserCreatedResponse(
            toResponse(created.account()), mode, delivery != null && delivery.emailSent());
    if (delivery != null) {
      response.setDeliveryPath(delivery.path());
      response.setSetupUrl(delivery.link());
    }
    response.setInitialPassword(created.initialPassword());
    return response;
  }

  static LocalUserPasswordResetResponse toPasswordReset(LinkDelivery delivery) {
    LocalUserPasswordResetResponse response =
        new LocalUserPasswordResetResponse(delivery.emailSent(), delivery.path());
    response.setSetupUrl(delivery.link());
    return response;
  }

  static LocalAuthSettingsResponse toSettings(
      LocalAuthSettingsService.View view, Integer revokedSessions) {
    LocalAuthSettings.Values values = view.values();
    LocalAuthSettingsResponse response =
        new LocalAuthSettingsResponse(
            view.enabled(),
            values.selfRegistrationEnabled(),
            values.selfRegistrationAllowedDomains(),
            values.passwordResetEnabled(),
            values.passwordMinLength(),
            values.invitationTokenTtlHours(),
            values.resetTokenTtlMinutes(),
            values.defaultExpiryDays(),
            values.inactiveDays(),
            view.publicBaseUrlConfigured());
    response.setRevokedSessions(revokedSessions);
    return response;
  }
}
