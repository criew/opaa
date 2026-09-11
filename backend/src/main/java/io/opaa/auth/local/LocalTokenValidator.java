package io.opaa.auth.local;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProviderRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The revocation validator of the local issuer (ADR-0033, Entscheidung 8), run for every request
 * after signature, issuer and expiry have been verified: the {@code jti} denylist (cache), the
 * account's {@code local_credentials} row (one primary-key lookup - the derived state, {@code
 * password_invalidated_before}) and the management switch (the registry's flag; the {@code users}
 * row is loaded only when the switch is off, to let a {@code SYSTEM_ADMIN} through). Every refusal
 * is a {@link LocalTokenRejection} naming its reason; a token whose subject has no local account is
 * refused rather than provisioned.
 */
@Component
public class LocalTokenValidator {

  private final LocalCredentialsRepository credentials;
  private final UserRepository users;
  private final LocalRefreshTokenRepository refreshTokens;
  private final LocalTokenRevocationService revocation;
  private final OidcProviderRegistry registry;
  private final Clock clock;

  public LocalTokenValidator(
      LocalCredentialsRepository credentials,
      UserRepository users,
      LocalRefreshTokenRepository refreshTokens,
      LocalTokenRevocationService revocation,
      OidcProviderRegistry registry,
      Clock clock) {
    this.credentials = credentials;
    this.users = users;
    this.refreshTokens = refreshTokens;
    this.revocation = revocation;
    this.registry = registry;
    this.clock = clock;
  }

  public Optional<LocalTokenRejection> rejectionFor(Jwt jwt) {
    String jti = jwt.getId();
    Instant issuedAt = jwt.getIssuedAt();
    UUID userId = subjectOf(jwt);
    if (jti == null || issuedAt == null || userId == null) {
      return Optional.of(new LocalTokenRejection(LocalTokenMarkers.MALFORMED_TOKEN, null));
    }
    if (revocation.isDenylisted(jti)) {
      return Optional.of(
          new LocalTokenRejection(
              LocalTokenMarkers.SESSION_REVOKED, latestRevocationCause(userId)));
    }
    LocalCredentials row = credentials.findById(userId).orElse(null);
    if (row == null) {
      return Optional.of(new LocalTokenRejection(LocalTokenMarkers.UNKNOWN_ACCOUNT, null));
    }
    Instant now = clock.instant();
    switch (row.state(now)) {
      case LOCKED -> {
        return Optional.of(
            new LocalTokenRejection(LocalTokenMarkers.ACCOUNT_LOCKED, lockCause(row)));
      }
      case EXPIRED -> {
        return Optional.of(new LocalTokenRejection(LocalTokenMarkers.ACCOUNT_EXPIRED, null));
      }
      default -> {
        // ACTIVE; INVITED cannot hold a token - there is no password to sign in with
      }
    }
    if (LocalTokenRevocationService.issuedBefore(issuedAt, row.getPasswordInvalidatedBefore())) {
      return Optional.of(
          new LocalTokenRejection(
              LocalTokenMarkers.SESSION_REVOKED, latestRevocationCause(userId)));
    }
    if (!registry.localAccountsEnabled() && !isSystemAdmin(userId)) {
      return Optional.of(new LocalTokenRejection(LocalTokenMarkers.LOCAL_ACCOUNTS_DISABLED, null));
    }
    return Optional.empty();
  }

  private boolean isSystemAdmin(UUID userId) {
    return users
        .findById(userId)
        .map(User::getSystemRole)
        .filter(role -> role == SystemRole.SYSTEM_ADMIN)
        .isPresent();
  }

  /** A temporary lockout without a stored reason is the one after failed sign-ins. */
  private static String lockCause(LocalCredentials row) {
    LockReason reason = row.getLockedReason();
    if (reason == null) {
      reason = LockReason.FAILED_LOGINS;
    }
    return LocalTokenRejection.causeOf(reason);
  }

  /**
   * The cause of the most recent revocation of one of the account's refresh families - the act that
   * ended the sessions; only read on the refusal path.
   */
  private String latestRevocationCause(UUID userId) {
    return refreshTokens
        .findFirstByUserIdAndRevokedAtIsNotNullOrderByRevokedAtDesc(userId)
        .map(LocalRefreshToken::getRevocationReason)
        .map(LocalTokenRejection::causeOf)
        .orElse(null);
  }

  private static UUID subjectOf(Jwt jwt) {
    String subject = jwt.getSubject();
    if (subject == null) {
      return null;
    }
    try {
      return UUID.fromString(subject);
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }
}
