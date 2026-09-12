package io.opaa.auth.local;

import io.opaa.common.ValidationException;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single-use links of the local account management (ADR-0033, Entscheidungen 3 and 11):
 * invitation, password reset, address confirmation, handover. A raw token is 256 bits of randomness
 * in base64url and exists only in the link; the table holds its HMAC under {@link
 * Purpose#ACTION_TOKEN_LOOKUP}, so a rotation of the secret voids every open link. Issuing a link
 * consumes the open ones of the same purpose; redeeming is the atomic {@link
 * LocalActionTokenRepository#markConsumed}, so two redemptions of one link cannot both succeed.
 */
@Service
public class LocalActionTokenService {

  /** The one code every refused link answers with - unknown, expired and consumed alike. */
  public static final String TOKEN_INVALID = "TOKEN_INVALID";

  static final String TOKEN_INVALID_MESSAGE = "Dieser Link ist nicht mehr gültig.";

  private static final int TOKEN_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final LocalActionTokenRepository repository;
  private final LocalAuthKeyService keys;
  private final Clock clock;

  public LocalActionTokenService(
      LocalActionTokenRepository repository, LocalAuthKeyService keys, Clock clock) {
    this.repository = repository;
    this.keys = keys;
    this.clock = clock;
  }

  /** Issues a fresh link valid for {@code ttl}; older open links of the purpose are consumed. */
  @Transactional
  public IssuedActionToken issue(UUID userId, ActionTokenPurpose purpose, Duration ttl) {
    Instant now = clock.instant();
    repository.consumeOpenTokens(userId, purpose, now);
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expiresAt = now.plus(ttl);
    repository.save(new LocalActionToken(userId, purpose, hash(raw), now, expiresAt));
    return new IssuedActionToken(raw, expiresAt);
  }

  /**
   * Consumes every open link of the purpose without issuing a new one - after an act that makes an
   * older link a liability: a generated password, a lock, a changed address (ADR-0033, Entscheidung
   * 11). Returns how many links were closed.
   */
  @Transactional
  public int consumeOpen(UUID userId, ActionTokenPurpose purpose) {
    return repository.consumeOpenTokens(userId, purpose, clock.instant());
  }

  /** The open, unexpired token behind {@code rawToken} for {@code purpose}, if any. */
  @Transactional(readOnly = true)
  public Optional<LocalActionToken> findRedeemable(String rawToken, ActionTokenPurpose purpose) {
    if (rawToken == null || rawToken.isBlank()) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    return repository
        .findByTokenHashAndPurpose(hash(rawToken), purpose)
        .filter(token -> token.isRedeemable(now));
  }

  /**
   * Consumes the token behind {@code rawToken} and returns it as stored after the consumption -
   * exactly once: a second call, a concurrent one, an expired or unknown token all yield empty.
   */
  @Transactional
  public Optional<LocalActionToken> redeem(String rawToken, ActionTokenPurpose purpose) {
    Optional<LocalActionToken> token = findRedeemable(rawToken, purpose);
    if (token.isEmpty()) {
      return Optional.empty();
    }
    if (repository.markConsumed(token.get().getId(), clock.instant()) != 1) {
      return Optional.empty();
    }
    // re-read: the bulk update cleared the persistence context, the instance above is detached
    // and still says consumedAt == null
    return repository.findById(token.get().getId());
  }

  /** The 400 a link endpoint answers with for every refused token (ADR-0033, Entscheidung 11). */
  public static ValidationException invalidToken() {
    return new ValidationException(TOKEN_INVALID_MESSAGE, TOKEN_INVALID);
  }

  private String hash(String rawToken) {
    return keys.lookupHash(Purpose.ACTION_TOKEN_LOOKUP, rawToken);
  }

  /** A freshly issued link: the raw value for the link and its expiry. */
  public record IssuedActionToken(String rawToken, Instant expiresAt) {

    /** Never the token: an issued link may be logged, its secret never. */
    @Override
    public String toString() {
      return "IssuedActionToken[expiresAt=" + expiresAt + "]";
    }
  }
}
