package io.opaa.account;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.security.LocalAuthKeyService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two layers of access-token revocation (ADR-0033, Entscheidung 7; ADR-0053, Entscheidung 6): a
 * {@code jti} denylist for single tokens (sign-out) and the session generation of the account for
 * everything at once.
 *
 * <p>The denylist caches only "revoked", until the token would have expired anyway; "not revoked"
 * is read from {@code local_revoked_tokens} on every check (a primary-key lookup), so a sign-out on
 * any instance is refused on every instance from the next request on.
 *
 * <p>Ending every session raises {@code session_generation}, which a token carries as {@link
 * LocalAccessTokenService#SESSION_GENERATION_CLAIM}; no clock of any host takes part.
 */
@Service
public class LocalTokenRevocationService {

  private static final long CACHE_MAX_SIZE = 100_000;

  private final LocalRevokedTokenRepository denylist;
  private final LocalCredentialsRepository credentials;
  private final Clock clock;
  private final Cache<String, Boolean> denylisted;

  public LocalTokenRevocationService(
      LocalRevokedTokenRepository denylist,
      LocalCredentialsRepository credentials,
      LocalAuthProperties properties,
      Clock clock) {
    this.denylist = denylist;
    this.credentials = credentials;
    this.clock = clock;
    this.denylisted =
        Caffeine.newBuilder()
            .maximumSize(CACHE_MAX_SIZE)
            .expireAfterWrite(properties.accessTokenTtl())
            .build();
  }

  /** Denylists one access token until it would have expired anyway; effective on every instance. */
  @Transactional
  public void revokeAccessToken(String jti, UUID userId, Instant expiresAt) {
    String hash = LocalAuthKeyService.jtiHash(jti);
    if (!denylist.existsById(hash)) {
      denylist.save(new LocalRevokedToken(hash, userId, expiresAt, clock.instant()));
    }
    denylisted.put(hash, Boolean.TRUE);
  }

  public boolean isDenylisted(String jti) {
    String hash = LocalAuthKeyService.jtiHash(jti);
    if (denylisted.getIfPresent(hash) != null) {
      return true;
    }
    if (!denylist.existsById(hash)) {
      return false;
    }
    denylisted.put(hash, Boolean.TRUE);
    return true;
  }

  /**
   * Ends every session of the account by raising its session generation. A no-op for an account
   * without local credentials.
   */
  @Transactional
  public void endAllSessions(UUID userId) {
    Instant now = clock.instant();
    credentials
        .findById(userId)
        .ifPresent(
            row -> {
              row.endAllSessions(now);
              credentials.save(row);
            });
  }

  /**
   * Whether every session of {@code userId} ended after {@code session} was minted, read under a
   * share lock on the account's {@code local_credentials} row that lasts until the caller's
   * transaction ends. Every act that ends all sessions writes that row before it publishes {@link
   * LocalAccountSessionsEndedEvent}, so what the caller writes next on the session's behalf is
   * either refused here or seen by that act's listeners. {@code false} for an account without local
   * credentials and for a call without a session.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean sessionEndedSince(UUID userId, Jwt session) {
    List<Long> generation = credentials.lockSessionGeneration(userId);
    if (generation.isEmpty() || session == null) {
      return false;
    }
    return !(session.getClaim(LocalAccessTokenService.SESSION_GENERATION_CLAIM)
            instanceof Number number)
        || number.longValue() != generation.getFirst();
  }
}
