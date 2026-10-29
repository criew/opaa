package io.opaa.externalaccess.token;

import io.opaa.auth.AccountUsability;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.library.ExternalAccessProperties;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a presented bearer value into a person plus a token id, or into a named reason for
 * refusing it (ADR-0035, Entscheidung 2). The whole chain runs on <b>every single call</b>: switch,
 * token row, expiry, revocation and the lifecycle of the account behind it ({@link
 * AccountUsability}). Nothing is cached and nothing is carried over from a previous call, so a
 * Notaus or a revocation takes effect with the next request of an already open connection.
 *
 * <p>An account of an identity provider is accepted only while its last sign-in lies at most {@link
 * ExternalAccessProperties#providerSignInMaxAgeDays} back: a deactivation at the provider and the
 * groups of its claim reach OPAA only with a sign-in, so the bound is how stale they may get.
 *
 * <p>It costs one indexed lookup by HMAC hash - the price ADR-0035 accepts for a Sofortwiderruf
 * that a self-contained token could not give.
 *
 * <p>No method here logs, and no caller may log what it hands in: the value and its prefix stay out
 * of every log line (see the package Javadoc).
 */
@Service
public class ExternalAccessTokenAuthenticator {

  private final ExternalAccessTokenRepository tokens;
  private final UserRepository users;
  private final AccountUsability usability;
  private final LocalAuthKeyService keys;
  private final ExternalAccessSettingsService settings;
  private final Clock clock;
  private final FeatureFlags featureFlags;
  private final Duration providerSignInMaxAge;

  public ExternalAccessTokenAuthenticator(
      ExternalAccessTokenRepository tokens,
      UserRepository users,
      AccountUsability usability,
      LocalAuthKeyService keys,
      ExternalAccessSettingsService settings,
      Clock clock,
      FeatureFlags featureFlags,
      ExternalAccessProperties properties) {
    this.tokens = tokens;
    this.users = users;
    this.usability = usability;
    this.keys = keys;
    this.settings = settings;
    this.clock = clock;
    this.featureFlags = featureFlags;
    this.providerSignInMaxAge = Duration.ofDays(properties.providerSignInMaxAgeDays());
  }

  /** The per-call check; see the class Javadoc for its order and its cost. */
  @Transactional(readOnly = true)
  public Result authenticate(String rawValue) {
    if (switchedOff()) {
      return new Result.Refused(ExternalAccessTokenRejection.CHANNEL_CLOSED);
    }
    if (!settings.isEnabled()) {
      // Checked before the lookup: a closed channel answers the same way for every value, so a
      // Notaus does not turn into an oracle for which values exist.
      return new Result.Refused(ExternalAccessTokenRejection.CHANNEL_CLOSED);
    }
    return check(rawValue);
  }

  /**
   * The same chain with the installation switch evaluated <b>last</b>: a refusal of {@link
   * ExternalAccessTokenRejection#CHANNEL_CLOSED} then means "this value would work if the channel
   * were open".
   *
   * <p>Exactly one path may ask this, and it pays for it: it becomes an oracle for which values
   * exist while the channel is closed. The MCP endpoint owes a closed channel two different answers
   * - {@code 503} for a usable token, {@code 404} for anything else (ADR-0035) - and cannot tell
   * them apart without checking the value first.
   */
  @Transactional(readOnly = true)
  public Result authenticateWithSwitchLast(String rawValue) {
    if (switchedOff()) {
      return new Result.Refused(ExternalAccessTokenRejection.CHANNEL_CLOSED);
    }
    Result result = check(rawValue);
    if (!settings.isEnabled()) {
      return result instanceof Result.Authenticated
          ? new Result.Refused(ExternalAccessTokenRejection.CHANNEL_CLOSED)
          : result;
    }
    return result;
  }

  /**
   * The feature switched off for the installation (ADR-0044): nothing authenticates and no value is
   * looked up, on either path - the oracle {@link #authenticateWithSwitchLast} pays for would serve
   * a channel that does not exist.
   */
  private boolean switchedOff() {
    return !featureFlags.isEnabled(Feature.EXTERNAL_ACCESS);
  }

  private Result check(String rawValue) {
    if (!ExternalAccessTokenValues.looksLikeAccessToken(rawValue)) {
      return new Result.Refused(ExternalAccessTokenRejection.INVALID_TOKEN);
    }
    Optional<ExternalAccessToken> found =
        tokens.findByTokenLookupHash(
            keys.lookupHash(Purpose.EXTERNAL_ACCESS_TOKEN_LOOKUP, rawValue));
    if (found.isEmpty()) {
      return new Result.Refused(ExternalAccessTokenRejection.INVALID_TOKEN);
    }
    ExternalAccessToken token = found.get();
    Instant now = clock.instant();
    if (token.getRevokedAt() != null) {
      return new Result.Refused(
          token.getRevocationReason() == ExternalAccessTokenRevocationReason.EXPIRED
              ? ExternalAccessTokenRejection.TOKEN_EXPIRED
              : ExternalAccessTokenRejection.TOKEN_REVOKED);
    }
    if (!token.getExpiresAt().isAfter(now)) {
      return new Result.Refused(ExternalAccessTokenRejection.TOKEN_EXPIRED);
    }
    User user = users.findById(token.getUserId()).orElse(null);
    if (user == null) {
      return new Result.Refused(ExternalAccessTokenRejection.INVALID_TOKEN);
    }
    if (!usability.stateOf(user).isUsable()) {
      // A token that outlives a lock, an expiry or the provider of its account would be the most
      // convenient way around the account lifecycle; inactivity is a rule for connections only.
      return new Result.Refused(ExternalAccessTokenRejection.ACCOUNT_NOT_ACTIVE);
    }
    if (providerSignInTooOld(user, now)) {
      return new Result.Refused(ExternalAccessTokenRejection.SIGN_IN_REQUIRED);
    }
    return new Result.Authenticated(user, token.getId(), token.getLastUsedOn());
  }

  /**
   * A local account is left out: its lifecycle is decided in OPAA itself. One that never signed in
   * counts from its creation, as for the inactivity of {@link AccountUsability}.
   */
  private boolean providerSignInTooOld(User user, Instant now) {
    if (LocalIssuer.URN.equals(user.getIssuer())) {
      return false;
    }
    Instant lastSignIn =
        user.getLastLoginAt() != null ? user.getLastLoginAt() : user.getCreatedAt();
    return lastSignIn == null || lastSignIn.isBefore(now.minus(providerSignInMaxAge));
  }

  /** Outcome of {@link #authenticate}. */
  public sealed interface Result {

    /**
     * The person behind the value, the token id every scope check needs and the day of use the row
     * already carries - so the caller can skip a second write without a second query.
     */
    record Authenticated(User user, UUID tokenId, LocalDate lastUsedOn) implements Result {}

    /** Refused, with the reason {@code WWW-Authenticate} names. */
    record Refused(ExternalAccessTokenRejection rejection) implements Result {}
  }
}
