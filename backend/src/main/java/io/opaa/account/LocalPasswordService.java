package io.opaa.account;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.LockReason;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.LocalAccountAccess;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
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
 * current password and the setting through an invitation, reset or verification link. All check the
 * new password against the {@link PasswordPolicy}, replace the hash, clear a forced change,
 * invalidate every access token issued up to now and revoke every refresh family ({@code
 * PASSWORD_CHANGED} - the trace a later refused token names as its cause). One transaction each: a
 * failing revocation rolls the new hash back too, so the person never ends up with a new password
 * and old sessions still alive, and a policy refusal rolls the redemption of a link back, so the
 * link stays open.
 */
@Service
public class LocalPasswordService {

  static final String WRONG_PASSWORD = "WRONG_PASSWORD";

  private final LocalCredentialsRepository credentials;
  private final UserRepository users;
  private final LocalActionTokenService actionTokens;
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
      PasswordEncoder passwordEncoder,
      PasswordPolicy policy,
      LocalTokenRevocationService revocation,
      LocalRefreshTokenService refreshTokens,
      AuditEventRecorder audit,
      Clock clock) {
    this.credentials = credentials;
    this.users = users;
    this.actionTokens = actionTokens;
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
   * account that may no longer redeem a link ({@link LocalAccountAccess#mayRedeemLink}). An
   * invitation link confirms the address - the invitation is the administrator's act of vouching
   * for it; a reset link does not, so a reset of a self-registered, unconfirmed account leaves the
   * confirmation to the verification link. The act is audited as {@code LOCAL_PASSWORD_SET} with
   * the link's purpose; see {@link #applyLinkPassword} for the rest.
   */
  @Transactional
  public User setPasswordByLink(String rawToken, String newPassword) {
    LocalActionToken token =
        actionTokens
            .redeem(rawToken, ActionTokenPurpose.SET_PASSWORD)
            .or(() -> actionTokens.redeem(rawToken, ActionTokenPurpose.RESET_PASSWORD))
            .orElseThrow(LocalActionTokenService::invalidToken);
    User user = applyLinkPassword(token, newPassword);
    record(user, AuditEventType.LOCAL_PASSWORD_SET, Map.of("purpose", token.getPurpose().name()));
    return user;
  }

  /**
   * Redeems the {@code VERIFY_EMAIL} link of a self-registration - exactly once - confirms the
   * address and sets the password chosen now (ADR-0033, Entscheidung 11). Any hash stored before is
   * discarded: whoever registered the address first never decides the password, only whoever holds
   * the mailbox does. Refusals as in {@link #setPasswordByLink}; see {@link #applyLinkPassword}.
   */
  @Transactional
  public User confirmRegistration(String rawToken, String newPassword) {
    LocalActionToken token =
        actionTokens
            .redeem(rawToken, ActionTokenPurpose.VERIFY_EMAIL)
            .orElseThrow(LocalActionTokenService::invalidToken);
    return applyLinkPassword(token, newPassword);
  }

  /**
   * The password write of a redeemed link: replaces the hash, clears a forced change, confirms the
   * address unless the link is a reset, lifts a failed-login lockout, consumes every other open
   * password link and ends every session issued up to now. A policy refusal throws before any
   * write, so the transaction rolls the redemption back and the link stays open.
   */
  private User applyLinkPassword(LocalActionToken token, String newPassword) {
    Instant now = clock.instant();
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
    if (token.getPurpose() != ActionTokenPurpose.RESET_PASSWORD
        && row.getEmailVerifiedAt() == null) {
      row.markEmailVerified(now);
    }
    if (row.getLockedReason() == LockReason.FAILED_LOGINS) {
      row.unlock(now);
    } else {
      row.resetFailedLoginAttempts(now);
    }
    credentials.save(row);
    revocation.invalidateSessionsIssuedBefore(user.getId());
    actionTokens.consumeOpen(user.getId(), ActionTokenPurpose.SET_PASSWORD);
    actionTokens.consumeOpen(user.getId(), ActionTokenPurpose.RESET_PASSWORD);
    refreshTokens.revokeAllForUser(user.getId(), RevocationReason.PASSWORD_CHANGED);
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
