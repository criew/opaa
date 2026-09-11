package io.opaa.auth.local;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProviderRegistry;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The refresh-token families of local sessions (ADR-0033, Entscheidung 7). A raw token is 256 bits
 * of randomness in base64url; only its HMAC lookup hash is stored. A sign-in opens a family with
 * the idle limit and the absolute end of the account's role; a rotation revokes the presented token
 * as {@code ROTATED} and issues a successor of the same family whose idle limit never passes the
 * family's end. The presentation of an already <em>rotated</em> token - or losing the atomic
 * rotation to a concurrent refresh with the same cookie - is a replay: every family of the account
 * is revoked as {@code REUSE_DETECTED}, every access token of the account is invalidated, the act
 * is audited as {@code LOCAL_SESSION_REVOKED} and logged at WARN with the account id, never the
 * address.
 *
 * <p>Transactions: successor insert and {@link LocalRefreshTokenRepository#rotateIfActive} form one
 * transaction that is rolled back when the rotation was lost, so the loser leaves no active
 * successor behind; the replay handling then runs in a transaction of its own. Failure directions:
 * if the rotation transaction fails, nothing changed and the presented token stays active for a
 * retry; if the replay transaction fails, the presented token is already revoked and the next
 * presentation detects the replay again.
 *
 * <p>A rotation is refused like an unknown token when the account is no longer login-capable or the
 * management switch excludes it: a session that could only mint tokens the validator refuses ends
 * here instead of looping. Two concurrent refreshes with the same cookie are a replay by ADR-0033 -
 * the SPA serialises {@code /refresh}.
 */
@Service
public class LocalRefreshTokenService {

  /** The audit actor of the issuer's own acts (ADR-0033, Entscheidung 13). */
  public static final String SYSTEM_ACTOR = "local-auth";

  private static final Logger log = LoggerFactory.getLogger(LocalRefreshTokenService.class);
  private static final int TOKEN_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final LocalRefreshTokenRepository repository;
  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final OidcProviderRegistry registry;
  private final LocalAuthKeyService keys;
  private final LocalAuthProperties properties;
  private final LocalTokenRevocationService revocation;
  private final AuditEventRecorder audit;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public LocalRefreshTokenService(
      LocalRefreshTokenRepository repository,
      UserRepository users,
      LocalCredentialsRepository credentials,
      OidcProviderRegistry registry,
      LocalAuthKeyService keys,
      LocalAuthProperties properties,
      LocalTokenRevocationService revocation,
      AuditEventRecorder audit,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.repository = repository;
    this.users = users;
    this.credentials = credentials;
    this.registry = registry;
    this.keys = keys;
    this.properties = properties;
    this.revocation = revocation;
    this.audit = audit;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** Opens a new family for the account - a sign-in. */
  public IssuedRefreshToken issue(User user) {
    Instant now = clock.instant();
    Limits limits = limitsFor(user);
    Instant familyExpiresAt = now.plus(limits.sessionMaxLifetime());
    return insert(UUID.randomUUID(), user.getId(), now, now.plus(limits.idle()), familyExpiresAt);
  }

  /** Rotates a presented raw token; see the class Javadoc for the replay semantics. */
  public RotationResult rotate(String rawToken) {
    Instant now = clock.instant();
    LocalRefreshToken presented = findPresented(rawToken).orElse(null);
    if (presented == null) {
      return new RotationResult.Unknown();
    }
    if (presented.getRevocationReason() == RevocationReason.ROTATED) {
      handleReuse(presented, now);
      return new RotationResult.Reused(presented.getUserId());
    }
    if (presented.getRevokedAt() != null) {
      // ended by a sign-out, a password change or an act of administration: the other devices of
      // the account simply learn that their session is over - no replay, no warning
      return new RotationResult.Unknown();
    }
    if (!presented.isActive(now)) {
      return new RotationResult.Unknown();
    }
    User user = users.findById(presented.getUserId()).orElse(null);
    LocalCredentials row = credentials.findById(presented.getUserId()).orElse(null);
    if (user == null
        || row == null
        || !LocalAccountAccess.isLoginCapable(row, now)
        || !LocalAccountAccess.passesManagementSwitch(registry, user)) {
      return new RotationResult.Unknown();
    }
    Instant expiresAt = now.plus(limitsFor(user).idle());
    if (expiresAt.isAfter(presented.getFamilyExpiresAt())) {
      expiresAt = presented.getFamilyExpiresAt();
    }
    Instant successorExpiresAt = expiresAt;
    IssuedRefreshToken successor =
        transactions.execute(
            status -> {
              IssuedRefreshToken issued =
                  insert(
                      presented.getFamilyId(),
                      presented.getUserId(),
                      now,
                      successorExpiresAt,
                      presented.getFamilyExpiresAt());
              int rotated =
                  repository.rotateIfActive(
                      presented.getId(), issued.id(), RevocationReason.ROTATED, now);
              if (rotated == 0) {
                status.setRollbackOnly();
                return null;
              }
              return issued;
            });
    if (successor == null) {
      handleReuse(presented, now);
      return new RotationResult.Reused(presented.getUserId());
    }
    return new RotationResult.Rotated(user, successor);
  }

  /** Revokes the family behind a presented raw token; {@code false} when none matched. */
  public boolean revokePresentedFamily(String rawToken, RevocationReason reason) {
    return findPresented(rawToken)
        .map(row -> repository.revokeFamily(row.getFamilyId(), reason, clock.instant()) > 0)
        .orElse(false);
  }

  public int revokeAllForUser(UUID userId, RevocationReason reason) {
    return repository.revokeAllForUser(userId, reason, clock.instant());
  }

  public int revokeAllForUserExcept(UUID userId, UUID keepFamilyId, RevocationReason reason) {
    return repository.revokeAllForUserExceptFamily(userId, keepFamilyId, reason, clock.instant());
  }

  public Optional<LocalRefreshToken> findPresented(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      return Optional.empty();
    }
    return repository.findByTokenLookupHash(
        keys.lookupHash(Purpose.REFRESH_TOKEN_LOOKUP, rawToken));
  }

  /**
   * The replay response, in one transaction of its own: revoke the family, end every session of the
   * account, audit and warn - but only when the family still had an active member; a second replay
   * of an already dead family changes no state and writes no event.
   */
  private void handleReuse(LocalRefreshToken presented, Instant now) {
    UUID userId = presented.getUserId();
    Integer revoked =
        transactions.execute(
            status -> {
              int rows =
                  repository.revokeFamily(
                      presented.getFamilyId(), RevocationReason.REUSE_DETECTED, now);
              if (rows > 0) {
                repository.revokeAllForUser(userId, RevocationReason.REUSE_DETECTED, now);
                revocation.invalidateSessionsIssuedBefore(userId);
                users.findById(userId).ifPresent(this::auditSessionsRevoked);
              }
              return rows;
            });
    log.warn(
        "Refresh token reuse detected for local account {}: family {} ({} active member(s))"
            + " and every other session of the account revoked",
        userId,
        presented.getFamilyId(),
        revoked);
  }

  private void auditSessionsRevoked(User user) {
    UUID pseudonym = audit.pseudonymFor(user.getId(), user.getOrganizationId());
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(user.getOrganizationId())
            .actorRef(SYSTEM_ACTOR)
            .type(AuditEventType.LOCAL_SESSION_REVOKED)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, user.getId())
            .after(Map.of("reason", RevocationReason.REUSE_DETECTED.name()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private IssuedRefreshToken insert(
      UUID familyId, UUID userId, Instant now, Instant expiresAt, Instant familyExpiresAt) {
    String raw = generate();
    LocalRefreshToken saved =
        repository.save(
            new LocalRefreshToken(
                familyId,
                userId,
                keys.lookupHash(Purpose.REFRESH_TOKEN_LOOKUP, raw),
                now,
                expiresAt,
                familyExpiresAt));
    return new IssuedRefreshToken(
        raw, saved.getId(), familyId, expiresAt, Duration.between(now, expiresAt));
  }

  private Limits limitsFor(User user) {
    if (user.getSystemRole() == SystemRole.SYSTEM_ADMIN) {
      return new Limits(properties.adminRefreshTokenTtl(), properties.adminSessionMaxLifetime());
    }
    return new Limits(properties.refreshTokenTtl(), properties.sessionMaxLifetime());
  }

  private static String generate() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private record Limits(Duration idle, Duration sessionMaxLifetime) {}

  /** A freshly issued refresh token: the raw value for the cookie and its limits. */
  public record IssuedRefreshToken(
      String value, UUID id, UUID familyId, Instant expiresAt, Duration maxAge) {}

  /** Outcome of {@link #rotate}. */
  public sealed interface RotationResult {

    /** Rotated; the caller mints a new access token for {@code user}. */
    record Rotated(User user, IssuedRefreshToken token) implements RotationResult {}

    /** A replay: the family and every session of the account are revoked. */
    record Reused(UUID userId) implements RotationResult {}

    /** No active token matched - unknown, expired or of a deleted account. */
    record Unknown() implements RotationResult {}
  }
}
