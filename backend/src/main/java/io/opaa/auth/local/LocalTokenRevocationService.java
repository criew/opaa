package io.opaa.auth.local;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.security.LocalAuthKeyService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two layers of access-token revocation (ADR-0033, Entscheidung 7): a {@code jti} denylist for
 * single tokens (sign-out), fronted by a Caffeine cache that forgets an entry when the token would
 * have expired anyway, and {@code password_invalidated_before} for everything at once. The cutoff
 * is the start of the <em>next</em> whole second, because a token's {@code iat} has no finer
 * resolution: every token minted up to and including the current second is invalid, and the fresh
 * token {@code change-password} hands out in the same breath is minted with {@code iat} at the
 * cutoff ({@link LocalAccessTokenService#issue(io.opaa.auth.User, boolean, Instant)}).
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

  /** Denylists one access token until it would have expired anyway. */
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
    return Boolean.TRUE.equals(denylisted.get(hash, denylist::existsById));
  }

  /**
   * Invalidates every access token of the account issued up to the current second (see the class
   * Javadoc); returns the cutoff a replacement token must not be issued before. A no-op for an
   * account without local credentials.
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

  /** Whether a token issued at {@code issuedAt} falls under a stored cutoff. */
  static boolean issuedBefore(Instant issuedAt, Instant cutoff) {
    return cutoff != null && issuedAt.isBefore(cutoff);
  }

  /** The first whole second after {@code instant}. */
  static Instant cutoffFor(Instant instant) {
    return instant.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
  }
}
