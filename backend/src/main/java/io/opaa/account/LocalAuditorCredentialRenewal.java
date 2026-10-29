package io.opaa.account;

import io.opaa.account.LocalActionTokenService.IssuedActionToken;
import io.opaa.auth.AuditorRoleGrantedEvent;
import io.opaa.auth.AuditorRoles;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.User;
import io.opaa.common.ConflictException;
import io.opaa.common.PublicBaseUrl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A local account that receives the AUDITOR role starts with credentials only its person holds: the
 * password and every session and open link end, and a new invitation goes to the account's address
 * through the outbox, never displayed to anyone. The system administration created the account and
 * may know its password; without this, it would hold an auditor account of its own. Without a
 * public base URL or mail delivery the grant is refused and rolls back.
 */
@Component
public class LocalAuditorCredentialRenewal {

  private final LocalCredentialsRepository credentials;
  private final LocalAuthSettingsRepository settings;
  private final LocalActionTokenService actionTokens;
  private final LocalRefreshTokenRepository refreshTokens;
  private final LocalAccountMailer mailer;
  private final PublicBaseUrl publicBaseUrl;
  private final Clock clock;

  public LocalAuditorCredentialRenewal(
      LocalCredentialsRepository credentials,
      LocalAuthSettingsRepository settings,
      LocalActionTokenService actionTokens,
      LocalRefreshTokenRepository refreshTokens,
      LocalAccountMailer mailer,
      PublicBaseUrl publicBaseUrl,
      Clock clock) {
    this.credentials = credentials;
    this.settings = settings;
    this.actionTokens = actionTokens;
    this.refreshTokens = refreshTokens;
    this.mailer = mailer;
    this.publicBaseUrl = publicBaseUrl;
    this.clock = clock;
  }

  @EventListener
  public void onGranted(AuditorRoleGrantedEvent event) {
    User user = event.user();
    Optional<LocalCredentials> found = credentials.findById(user.getId());
    if (found.isEmpty()) {
      return;
    }
    if (!publicBaseUrl.isConfigured()) {
      throw mailRequired();
    }
    Instant now = clock.instant();
    LocalCredentials row = found.get();
    row.discardPassword(now);
    row.invalidateSessionsIssuedBefore(LocalTokenRevocationService.cutoffFor(now), now);
    credentials.save(row);
    actionTokens.consumeOpen(user.getId(), ActionTokenPurpose.SET_PASSWORD);
    actionTokens.consumeOpen(user.getId(), ActionTokenPurpose.RESET_PASSWORD);
    actionTokens.consumeOpen(user.getId(), ActionTokenPurpose.HANDOVER);
    refreshTokens.revokeAllForUser(user.getId(), RevocationReason.ADMIN, now);
    int ttlHours =
        settings
            .findSingleton()
            .map(LocalAuthSettings::values)
            .orElseGet(LocalAuthSettings.Values::defaults)
            .invitationTokenTtlHours();
    IssuedActionToken invitation =
        actionTokens.issue(
            user.getId(), ActionTokenPurpose.SET_PASSWORD, Duration.ofHours(ttlHours));
    if (!mailer.queueInvitation(user, invitation)) {
      throw mailRequired();
    }
  }

  private static ConflictException mailRequired() {
    return new ConflictException(
        "Ein lokales Konto erhält die Rolle Revision nur mit eingerichtetem E-Mail-Versand und"
            + " öffentlicher Adresse: Das neue Passwort setzt die Person selbst über einen Link"
            + " an ihre Adresse.",
        AuditorRoles.AUDITOR_ACCOUNT);
  }
}
