package io.opaa.auth.local;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.LockReason;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The person's own password writes (ADR-0033, Entscheidungen 8, 9 and 11): the change with the
 * current password and the setting through an invitation or reset link. Both check the new password
 * against the {@link PasswordPolicy}, replace the hash, clear a forced change, invalidate every
 * access token issued up to now and revoke every refresh family ({@code PASSWORD_CHANGED} - the
 * trace a later refused token names as its cause). One transaction each: a failing revocation rolls
 * the new hash back too, so the person never ends up with a new password and old sessions still
 * alive, and a policy refusal rolls the redemption of a link back, so the link stays open.
 */
@Service
public class LocalPasswordService {

  static final String WRONG_PASSWORD = "WRONG_PASSWORD";

  private final LocalCredentialsRepository credentials;
  private final UserRepository users;
  private final LocalActionTokenService actionTokens;
  private final LocalActionTokenRepository actionTokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final PasswordPolicy policy;
  private final LocalTokenRevocationService revocation;
  private final LocalRefreshTokenService refreshTokens;
  private final AuditEventRecorder audit;
  private final Clock clock;

  public LocalPasswordService(
      LocalCredentialsRepository credentials,
      UserRepository users,
      LocalActionTokenService actionTokens,
      LocalActionTokenRepository actionTokenRepository,
      PasswordEncoder passwordEncoder,
      PasswordPolicy policy,
      LocalTokenRevocationService revocation,
      LocalRefreshTokenService refreshTokens,
      AuditEventRecorder audit,
      Clock clock) {
    this.credentials = credentials;
    this.users = users;
    this.actionTokens = actionTokens;
    this.actionTokenRepository = actionTokenRepository;
    this.passwordEncoder = passwordEncoder;
    this.policy = policy;
    this.revocation = revocation;
    this.refreshTokens = refreshTokens;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * @return the credentials after the change (detached); their {@code password_invalidated_before}
   *     is the cutoff the replacement access token is minted at
   */
  @Transactional
  public LocalCredentials changePassword(User user, String currentPassword, String newPassword) {
    LocalCredentials row =
        credentials
            .findById(user.getId())
            .orElseThrow(
                () ->
                    new ConflictException(
                        "Das Passwort kann nur für ein lokales Konto geändert werden."));
    if (row.getPasswordHash() == null
        || !passwordEncoder.matches(currentPassword, row.getPasswordHash())) {
      throw FieldValidationException.of(
          "currentPassword", WRONG_PASSWORD, "Das aktuelle Passwort ist falsch.");
    }
    policy.require("newPassword", newPassword, user.getEmail());
    Instant now = clock.instant();
    row.setPasswordHash(passwordEncoder.encode(newPassword), now);
    row.clearPasswordChangeRequirement(now);
    credentials.save(row);
    revocation.invalidateSessionsIssuedBefore(user.getId());
    refreshTokens.revokeAllForUser(user.getId(), RevocationReason.PASSWORD_CHANGED);
    record(user, AuditEventType.LOCAL_PASSWORD_CHANGED, null);
    return credentials.findById(user.getId()).orElse(row);
  }

  /**
   * Redeems a {@code SET_PASSWORD} (invitation) or {@code RESET_PASSWORD} link - exactly once - and
   * sets the password. Every refused token is the same {@link
   * LocalActionTokenService#invalidToken}: unknown, expired, consumed, of another purpose, or of an
   * account that may no longer redeem a link ({@link LocalAccountAccess#mayRedeemLink}). The
   * redeemed link proves possession of the mailbox (or the administrator's hand-over), so an
   * unconfirmed address counts as confirmed; a failed-login lockout is lifted, every other open
   * password link of the account is consumed, and the act is audited as {@code LOCAL_PASSWORD_SET}
   * with the link's purpose.
   */
  @Transactional
  public User setPasswordByLink(String rawToken, String newPassword) {
    Instant now = clock.instant();
    LocalActionToken token =
        actionTokens
            .redeem(rawToken, ActionTokenPurpose.SET_PASSWORD)
            .or(() -> actionTokens.redeem(rawToken, ActionTokenPurpose.RESET_PASSWORD))
            .orElseThrow(LocalActionTokenService::invalidToken);
    User user =
        users
            .findById(token.getUserId())
            .filter(found -> LocalIssuer.URN.equals(found.getIssuer()))
            .orElseThrow(LocalActionTokenService::invalidToken);
    LocalCredentials row =
        credentials
            .findById(user.getId())
            .filter(found -> LocalAccountAccess.mayRedeemLink(found, now))
            .orElseThrow(LocalActionTokenService::invalidToken);
    policy.require("newPassword", newPassword, user.getEmail());
    row.setPasswordHash(passwordEncoder.encode(newPassword), now);
    row.clearPasswordChangeRequirement(now);
    if (row.getEmailVerifiedAt() == null) {
      row.markEmailVerified(now);
    }
    if (row.getLockedReason() == LockReason.FAILED_LOGINS) {
      row.unlock(now);
    } else {
      row.resetFailedLoginAttempts(now);
    }
    row.invalidateSessionsIssuedBefore(LocalTokenRevocationService.cutoffFor(now), now);
    credentials.save(row);
    actionTokenRepository.consumeOpenTokens(user.getId(), ActionTokenPurpose.SET_PASSWORD, now);
    actionTokenRepository.consumeOpenTokens(user.getId(), ActionTokenPurpose.RESET_PASSWORD, now);
    refreshTokens.revokeAllForUser(user.getId(), RevocationReason.PASSWORD_CHANGED);
    record(user, AuditEventType.LOCAL_PASSWORD_SET, Map.of("purpose", token.getPurpose().name()));
    return user;
  }

  private void record(User user, AuditEventType type, Map<String, Object> after) {
    UUID pseudonym = audit.pseudonymFor(user.getId(), user.getOrganizationId());
    audit.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(user.getOrganizationId())
            .actor(user.getId())
            .type(type)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, user.getId())
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
