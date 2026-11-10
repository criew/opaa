package io.opaa.account;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.LocalAccountAccess;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The refresh-token families of local sessions (ADR-0033, Entscheidung 7). A raw token is 256 bits
 * of randomness in base64url; only its HMAC lookup hash is stored. A sign-in opens a family with
 * the idle limit and the absolute end of the account's role; a rotation revokes the presented token
 * as {@code ROTATED} and issues a successor of the same family whose idle limit never passes the
 * family's end.
 *
 * <p>An already <em>rotated</em> token presented again is renewed once more within {@code
 * refresh-grace-period} after its rotation if its successor is still active - never used, never
 * revoked - and the account may still sign in: the unused successor is rotated away atomically and
 * a new one issued, the reload that discarded the rotation's answer. Every other presentation of a
 * rotated token is a replay: every family of the account is revoked as {@code REUSE_DETECTED},
 * every access token of the account is invalidated, the act is audited as {@code
 * LOCAL_SESSION_REVOKED} and logged at WARN with the account id, never the address.
 *
 * <p>Concurrency: losing the atomic rotation to a concurrent refresh with the same cookie is judged
 * like a presentation of the then rotated token, once; losing the renewal within the grace period
 * to a concurrent one is a replay, so two renewals of the same token never both win. A rotation and
 * {@link #issueForSession} write under a share lock on the account's session generation, so no
 * token they write outlives a concurrent act that ends every session. Every act that revokes
 * refresh tokens and ends sessions, the replay handling included, writes or locks the account's
 * credentials row before it touches a token row; that one order rules out a deadlock with a
 * rotation and lets the act see the successor of a rotation it waited for.
 *
 * <p>Transactions: successor insert and the conditional updates form one transaction that is rolled
 * back when the rotation was lost, so the loser leaves no active successor behind; the replay
 * handling then runs in a transaction of its own. Failure directions: if the rotation transaction
 * fails, nothing changed and the presented token stays as it was for a retry; if the replay
 * transaction fails, the presented token is already revoked and the next presentation detects the
 * replay again.
 *
 * <p>A rotation is refused like an unknown token when the account is no longer login-capable or the
 * management switch excludes it: a session that could only mint tokens the validator refuses ends
 * here instead of looping.
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
  private final LocalAdminNetworkPolicy networkPolicy;
  private final TransactionTemplate transactions;
  private final ApplicationEventPublisher events;
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
      LocalAdminNetworkPolicy networkPolicy,
      PlatformTransactionManager transactionManager,
      ApplicationEventPublisher events,
      Clock clock) {
    this.repository = repository;
    this.users = users;
    this.credentials = credentials;
    this.registry = registry;
    this.keys = keys;
    this.properties = properties;
    this.revocation = revocation;
    this.audit = audit;
    this.networkPolicy = networkPolicy;
    this.transactions = new TransactionTemplate(transactionManager);
    this.events = events;
    this.clock = clock;
  }

  /** Opens a new family for the account - a sign-in. */
  public IssuedRefreshToken issue(User user) {
    Instant now = clock.instant();
    Limits limits = limitsFor(user);
    Instant familyExpiresAt = now.plus(limits.sessionMaxLifetime());
    return insert(UUID.randomUUID(), user.getId(), now, now.plus(limits.idle()), familyExpiresAt);
  }

  /**
   * {@link #issue} for a sign-in whose credentials were read at {@code sessionGeneration}, under a
   * share lock on the account's session generation: an act that ends every session either ended
   * them before - the generation moved, nothing is issued - or waits and then revokes the new
   * family with all others. Empty when the generation moved or the credentials are gone.
   */
  public Optional<IssuedRefreshToken> issueForSession(User user, long sessionGeneration) {
    return Optional.ofNullable(
        transactions.execute(
            status -> {
              Optional<Long> current = lockSessionGeneration(user.getId());
              if (current.isEmpty() || current.get() != sessionGeneration) {
                status.setRollbackOnly();
                return null;
              }
              return issue(user);
            }));
  }

  /**
   * Rotates a presented raw token; see the class Javadoc for the replay semantics. {@code
   * clientAddress} is the resolved client address: a local {@code SYSTEM_ADMIN} renews only from
   * the networks of {@link LocalAdminNetworkPolicy}, otherwise the token is refused like an unknown
   * one and stays as it was.
   */
  public RotationResult rotate(String rawToken, String clientAddress) {
    Instant now = clock.instant();
    LocalRefreshToken presented = findPresented(rawToken).orElse(null);
    if (presented == null) {
      return new RotationResult.Unknown();
    }
    if (presented.getRevocationReason() == RevocationReason.ROTATED) {
      return renewWithinGracePeriodOrReplay(presented, now, clientAddress);
    }
    if (presented.getRevokedAt() != null) {
      // ended by a sign-out, a password change or an act of administration: the other devices of
      // the account simply learn that their session is over - no replay, no warning
      return new RotationResult.Unknown();
    }
    if (!presented.isActive(now)) {
      return new RotationResult.Unknown();
    }
    User user = loginCapableOwner(presented, now);
    if (user == null) {
      return new RotationResult.Unknown();
    }
    if (!permitsNetwork(user, clientAddress)) {
      return new RotationResult.Unknown();
    }
    Successor successor =
        transactions.execute(
            status -> {
              Optional<Long> generation = lockSessionGeneration(user.getId());
              if (generation.isEmpty()) {
                status.setRollbackOnly();
                return Successor.NONE;
              }
              IssuedRefreshToken issued = insertSuccessor(presented, user, now);
              int rotated =
                  repository.rotateIfActive(
                      presented.getId(), issued.id(), RevocationReason.ROTATED, now);
              if (rotated == 0) {
                status.setRollbackOnly();
                return null;
              }
              return new Successor(issued, generation.get());
            });
    if (successor == Successor.NONE) {
      return new RotationResult.Unknown();
    }
    if (successor != null) {
      return new RotationResult.Rotated(user, successor.token(), successor.sessionGeneration());
    }
    LocalRefreshToken current = repository.findById(presented.getId()).orElse(null);
    if (current != null && current.getRevocationReason() == RevocationReason.ROTATED) {
      return renewWithinGracePeriodOrReplay(current, now, clientAddress);
    }
    handleReuse(presented, now);
    return new RotationResult.Reused(presented.getUserId());
  }

  /**
   * The presentation of an already rotated token: renewed when its rotation lies at most {@code
   * refresh-grace-period} back, its successor is still active and the account may sign in -
   * otherwise, or when a concurrent renewal takes the successor first, a replay.
   */
  private RotationResult renewWithinGracePeriodOrReplay(
      LocalRefreshToken presented, Instant now, String clientAddress) {
    LocalRefreshToken unusedSuccessor = unusedSuccessorWithinGracePeriod(presented, now);
    User user = unusedSuccessor == null ? null : loginCapableOwner(presented, now);
    if (user != null && !permitsNetwork(user, clientAddress)) {
      return new RotationResult.Unknown();
    }
    if (user != null) {
      Successor renewed =
          transactions.execute(
              status -> {
                Optional<Long> generation = lockSessionGeneration(user.getId());
                if (generation.isEmpty()) {
                  status.setRollbackOnly();
                  return Successor.NONE;
                }
                IssuedRefreshToken issued = insertSuccessor(presented, user, now);
                if (repository.rotateIfActive(
                            unusedSuccessor.getId(), issued.id(), RevocationReason.ROTATED, now)
                        == 0
                    || repository.redirectRotation(
                            presented.getId(), unusedSuccessor.getId(), issued.id())
                        == 0) {
                  status.setRollbackOnly();
                  return null;
                }
                return new Successor(issued, generation.get());
              });
      if (renewed == Successor.NONE) {
        return new RotationResult.Unknown();
      }
      if (renewed != null) {
        log.debug(
            "Rotated refresh token of local account {} presented again within the grace period;"
                + " its unused successor was replaced",
            presented.getUserId());
        return new RotationResult.Rotated(user, renewed.token(), renewed.sessionGeneration());
      }
    }
    handleReuse(presented, now);
    return new RotationResult.Reused(presented.getUserId());
  }

  /** The still active successor of a token rotated at most the grace period ago, or null. */
  private LocalRefreshToken unusedSuccessorWithinGracePeriod(
      LocalRefreshToken presented, Instant now) {
    Duration gracePeriod = properties.refreshGracePeriod();
    if (gracePeriod.isZero()
        || presented.getRotatedToId() == null
        || presented.getRevokedAt().plus(gracePeriod).isBefore(now)
        || !presented.getFamilyExpiresAt().isAfter(now)) {
      return null;
    }
    return repository
        .findById(presented.getRotatedToId())
        .filter(successor -> successor.isActive(now))
        .orElse(null);
  }

  /**
   * The network rule of the sign-in, applied to every renewal of a local administrator's session.
   */
  private boolean permitsNetwork(User user, String clientAddress) {
    if (networkPolicy.permitsSessionOf(user.getSystemRole(), clientAddress)) {
      return true;
    }
    log.warn(
        "Session renewal of local SYSTEM_ADMIN account {} refused: client address outside"
            + " OPAA_LOCAL_ADMIN_ALLOWED_CIDRS",
        user.getId());
    return false;
  }

  /** The owner of the token if the account may still sign in, otherwise null. */
  private User loginCapableOwner(LocalRefreshToken token, Instant now) {
    User user = users.findById(token.getUserId()).orElse(null);
    LocalCredentials row = credentials.findById(token.getUserId()).orElse(null);
    if (user == null
        || row == null
        || !LocalAccountAccess.isLoginCapable(row, now)
        || !LocalAccountAccess.passesManagementSwitch(registry, user)) {
      return null;
    }
    return user;
  }

  /**
   * The successor's limits follow the account's role at this rotation: a family opened under a
   * longer-lived role ends at most the role's absolute lifetime from now, so a promotion to {@code
   * SYSTEM_ADMIN} never keeps the regular session length.
   */
  private IssuedRefreshToken insertSuccessor(LocalRefreshToken presented, User user, Instant now) {
    Limits limits = limitsFor(user);
    Instant familyExpiresAt =
        earlier(presented.getFamilyExpiresAt(), now.plus(limits.sessionMaxLifetime()));
    Instant expiresAt = earlier(now.plus(limits.idle()), familyExpiresAt);
    return insert(presented.getFamilyId(), presented.getUserId(), now, expiresAt, familyExpiresAt);
  }

  private static Instant earlier(Instant first, Instant second) {
    return first.isBefore(second) ? first : second;
  }

  /**
   * The account's session generation under a share lock held to the end of the transaction; every
   * act that ends all sessions writes it before it revokes the refresh tokens, so it waits for a
   * family written here or has already revoked what it found.
   */
  private Optional<Long> lockSessionGeneration(UUID userId) {
    return credentials.lockSessionGeneration(userId).stream().findFirst();
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
   * account and what a session could have created ({@link LocalAccountSessionsEndedEvent}), audit
   * and warn - but only when the family still had an active member; a second replay of an already
   * dead family changes no state and writes no event, so a stale token cannot end the sessions of
   * the account again and again. The account's credentials row is locked first, like every other
   * act that ends sessions: a rotation of the family in flight commits before, and its successor is
   * then counted and revoked here.
   */
  private void handleReuse(LocalRefreshToken presented, Instant now) {
    UUID userId = presented.getUserId();
    Integer revoked =
        transactions.execute(
            status -> {
              credentials.findForUpdate(userId);
              int rows =
                  repository.revokeFamily(
                      presented.getFamilyId(), RevocationReason.REUSE_DETECTED, now);
              if (rows > 0) {
                repository.revokeAllForUser(userId, RevocationReason.REUSE_DETECTED, now);
                revocation.endAllSessions(userId);
                users
                    .findById(userId)
                    .ifPresent(
                        user -> {
                          credentials.flush();
                          events.publishEvent(
                              LocalAccountSessionsEndedEvent.bySystem(
                                  user, RevocationReason.REUSE_DETECTED, SYSTEM_ACTOR));
                          auditSessionsRevoked(user);
                        });
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

  /** A written successor and the session generation locked while it was written. */
  private record Successor(IssuedRefreshToken token, long sessionGeneration) {
    /** The account has no credentials any more: nothing was written. */
    static final Successor NONE = new Successor(null, -1);
  }

  /** A freshly issued refresh token: the raw value for the cookie and its limits. */
  public record IssuedRefreshToken(
      String value, UUID id, UUID familyId, Instant expiresAt, Duration maxAge) {}

  /** Outcome of {@link #rotate}. */
  public sealed interface RotationResult {

    /**
     * Rotated; the caller mints a new access token for {@code user}, and only while the account's
     * session generation is still {@code sessionGeneration}, the one locked during the rotation.
     */
    record Rotated(User user, IssuedRefreshToken token, long sessionGeneration)
        implements RotationResult {}

    /** A replay: every session of the account is revoked. */
    record Reused(UUID userId) implements RotationResult {}

    /** No active token matched - unknown, expired or of a deleted account. */
    record Unknown() implements RotationResult {}
  }
}
