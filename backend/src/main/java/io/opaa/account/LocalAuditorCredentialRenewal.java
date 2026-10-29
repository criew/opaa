package io.opaa.account;

import io.opaa.account.LocalActionTokenService.IssuedActionToken;
import io.opaa.auth.AuditorCredentialRenewal;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.User;
import io.opaa.common.PublicBaseUrl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A local account that receives the AUDITOR role starts with credentials only its person holds: the
 * password and every session and open link end, and one new link to set a password is issued with
 * the invitation lifetime. The link is returned, never mailed - SMTP settings and templates are in
 * the hands of the system administration, which created the account and may know its password.
 */
@Component
public class LocalAuditorCredentialRenewal implements AuditorCredentialRenewal {

  private final LocalCredentialsRepository credentials;
  private final LocalAuthSettingsRepository settings;
  private final LocalActionTokenService actionTokens;
  private final LocalRefreshTokenRepository refreshTokens;
  private final PublicBaseUrl publicBaseUrl;
  private final Clock clock;

  public LocalAuditorCredentialRenewal(
      LocalCredentialsRepository credentials,
      LocalAuthSettingsRepository settings,
      LocalActionTokenService actionTokens,
      LocalRefreshTokenRepository refreshTokens,
      PublicBaseUrl publicBaseUrl,
      Clock clock) {
    this.credentials = credentials;
    this.settings = settings;
    this.actionTokens = actionTokens;
    this.refreshTokens = refreshTokens;
    this.publicBaseUrl = publicBaseUrl;
    this.clock = clock;
  }

  /**
   * The link is absolute with a public base URL and a path relative to the installation without
   * one; empty for an account without local credentials.
   */
  @Override
  public Optional<String> renew(User user) {
    Optional<LocalCredentials> found = credentials.findById(user.getId());
    if (found.isEmpty()) {
      return Optional.empty();
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
    IssuedActionToken link =
        actionTokens.issue(
            user.getId(), ActionTokenPurpose.SET_PASSWORD, Duration.ofHours(ttlHours));
    return Optional.of(LocalAccountLinks.setPasswordLink(publicBaseUrl, link.rawToken()));
  }
}
