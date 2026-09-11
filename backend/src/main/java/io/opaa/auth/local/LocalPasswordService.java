package io.opaa.auth.local;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The person's own password change (ADR-0033, Entscheidungen 8, 9 and 11): the current password is
 * verified, the new one checked against the {@link PasswordPolicy}, the hash replaced, a forced
 * change cleared, every access token issued up to now invalidated, every refresh family revoked
 * ({@code PASSWORD_CHANGED} - the trace a later refused token names as its cause; the caller opens
 * a fresh family for the session the change was made from), and the act audited as {@code
 * LOCAL_PASSWORD_CHANGED}. One transaction: a failing revocation rolls the new hash back too, so
 * the person never ends up with a new password and old sessions still alive.
 */
@Service
public class LocalPasswordService {

  static final String WRONG_PASSWORD = "WRONG_PASSWORD";

  private final LocalCredentialsRepository credentials;
  private final PasswordEncoder passwordEncoder;
  private final PasswordPolicy policy;
  private final LocalTokenRevocationService revocation;
  private final LocalRefreshTokenService refreshTokens;
  private final AuditEventRecorder audit;
  private final Clock clock;

  public LocalPasswordService(
      LocalCredentialsRepository credentials,
      PasswordEncoder passwordEncoder,
      PasswordPolicy policy,
      LocalTokenRevocationService revocation,
      LocalRefreshTokenService refreshTokens,
      AuditEventRecorder audit,
      Clock clock) {
    this.credentials = credentials;
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
    UUID pseudonym = audit.pseudonymFor(user.getId(), user.getOrganizationId());
    audit.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(user.getOrganizationId())
            .actor(user.getId())
            .type(AuditEventType.LOCAL_PASSWORD_CHANGED)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, user.getId())
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return credentials.findById(user.getId()).orElse(row);
  }
}
