package io.opaa.account;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.security.LocalAuthKeyService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
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
 * LocalAccessTokenService#SESSION_GENERATION_CLAIM}; no clock of any host takes part. Beside it the
 * cutoff {@code password_invalidated_before} is still written, the start of the <em>next</em> whole
 * second: the previous version, running next to this one during a rolling update, refuses by it,
 * and so does this version for a token the previous one minted without the claim.
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
   * Ends every session of the account: raises its session generation and sets the cutoff (see the
   * class Javadoc); returns the cutoff a replacement token must not be issued before. A no-op for
   * an account without local credentials.
   */
  @Transactional
  public Instant invalidateSessionsIssuedBefore(UUID userId) {
    Instant now = clock.instant();
    Instant cutoff = cutoffFor(now);
    credentials
        .findById(userId)
        .ifPresent(
            row -> {
              row.invalidateSessionsIssuedBefore(cutoff, now);
              credentials.save(row);
            });
    return cutoff;
  }

  /**
   * Whether a token issued at {@code issuedAt} falls under a stored cutoff - only for a token
   * without the session generation claim, minted by the previous version during a rolling update.
   */
  static boolean issuedBefore(Instant issuedAt, Instant cutoff) {
    return cutoff != null && issuedAt.isBefore(cutoff);
  }

  /** The first whole second after {@code instant}. */
  public static Instant cutoffFor(Instant instant) {
    return instant.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
  }
}
