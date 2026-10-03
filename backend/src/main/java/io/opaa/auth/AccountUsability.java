package io.opaa.auth;

import io.opaa.api.types.LockReason;
import io.opaa.api.types.ProviderType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The one rule whether an account may be used right now (ADR-0041, Entscheidung 4), for every kind
 * of account: the directory lock first, a local account by its credentials, every other one by the
 * provider of its issuer. Inactivity counts only where a caller asks for it with a threshold.
 */
@Component
public class AccountUsability {

  private static final String DEV_MODE = "dev";

  /** The answer; only {@link #USABLE} may sign in, hold a token or release a secret. */
  public enum State {
    USABLE,
    /** A failed-login lockout, which ends by itself. */
    LOCKED_OUT,
    /** A local invitation not yet completed. */
    INVITED,
    DORMANT_PROVIDER_DISABLED,
    DORMANT_INACTIVE,
    /** Locked, expired, locked by the directory, or its provider is gone. */
    DEACTIVATED;

    public boolean isUsable() {
      return this == USABLE;
    }

    public boolean isDormant() {
      return this == DORMANT_PROVIDER_DISABLED || this == DORMANT_INACTIVE;
    }

    public boolean isDeactivated() {
      return this == DEACTIVATED;
    }
  }

  private final LocalCredentialsRepository credentials;
  private final OidcProviderRepository providers;
  private final AuthProperties authProperties;
  private final Clock clock;

  public AccountUsability(
      LocalCredentialsRepository credentials,
      OidcProviderRepository providers,
      AuthProperties authProperties,
      Clock clock) {
    this.credentials = credentials;
    this.providers = providers;
    this.authProperties = authProperties;
    this.clock = clock;
  }

  public State stateOf(User user) {
    return snapshot().stateOf(user);
  }

  /** The providers as they are now, read once for any number of accounts. */
  public Snapshot snapshot() {
    return snapshotWithoutProvider(null);
  }

  /**
   * The same, as if the provider {@code providerId} were switched off - what remains usable before
   * it is disabled or deleted. {@code null} leaves every provider as it is.
   */
  public Snapshot snapshotWithoutProvider(UUID providerId) {
    Map<String, Boolean> enabledByIssuer = new LinkedHashMap<>();
    for (OidcProvider provider : providers.findAllByOrderBySortOrderAscDisplayNameAsc()) {
      if (provider.getProviderType() == ProviderType.OIDC) {
        enabledByIssuer.put(
            OidcIssuerUris.normalize(provider.getIssuerUri()),
            provider.isEnabled() && !provider.getId().equals(providerId));
      }
    }
    String devIssuer =
        DEV_MODE.equals(authProperties.mode())
            ? OidcIssuerUris.normalize(authProperties.dev().issuer())
            : null;
    return new Snapshot(enabledByIssuer, devIssuer, clock.instant(), null);
  }

  /** One reading of the providers and the clock; see {@link AccountUsability}. */
  public final class Snapshot {

    private final Map<String, Boolean> enabledByIssuer;
    private final String devIssuer;
    private final Instant now;
    private final Duration inactivityThreshold;

    private Snapshot(
        Map<String, Boolean> enabledByIssuer,
        String devIssuer,
        Instant now,
        Duration inactivityThreshold) {
      this.enabledByIssuer = enabledByIssuer;
      this.devIssuer = devIssuer;
      this.now = now;
      this.inactivityThreshold = inactivityThreshold;
    }

    /**
     * The same reading that reports a usable account without sign-in for longer than {@code
     * threshold} as {@link State#DORMANT_INACTIVE}.
     */
    public Snapshot withInactivityThreshold(Duration threshold) {
      return new Snapshot(
          enabledByIssuer, devIssuer, now, Objects.requireNonNull(threshold, "threshold"));
    }

    public State stateOf(User user) {
      LocalCredentials row = isLocal(user) ? credentials.findById(user.getId()).orElse(null) : null;
      return stateOf(user, row);
    }

    /** The states of {@code users} by id, with one query for all local rows. */
    public Map<UUID, State> statesOf(Collection<User> users) {
      Map<UUID, LocalCredentials> rows =
          credentials
              .findAllById(
                  users.stream().filter(AccountUsability::isLocal).map(User::getId).toList())
              .stream()
              .collect(Collectors.toMap(LocalCredentials::getUserId, Function.identity()));
      Map<UUID, State> states = new LinkedHashMap<>();
      for (User user : users) {
        states.put(user.getId(), stateOf(user, rows.get(user.getId())));
      }
      return states;
    }

    private State stateOf(User user, LocalCredentials row) {
      State state = withoutInactivity(user, row);
      if (state == State.USABLE && inactivityThreshold != null && isInactive(user)) {
        return State.DORMANT_INACTIVE;
      }
      return state;
    }

    private State withoutInactivity(User user, LocalCredentials row) {
      if (user.isDirectoryLocked()) {
        return State.DEACTIVATED;
      }
      if (isLocal(user)) {
        return row == null ? State.DEACTIVATED : localState(row);
      }
      String issuer = OidcIssuerUris.normalize(user.getIssuer());
      if (devIssuer != null && devIssuer.equals(issuer)) {
        return State.USABLE;
      }
      Boolean enabled = enabledByIssuer.get(issuer);
      if (enabled == null) {
        return State.DEACTIVATED;
      }
      return enabled ? State.USABLE : State.DORMANT_PROVIDER_DISABLED;
    }

    /** The expiry is read before the lock, which would otherwise hide it. */
    private State localState(LocalCredentials row) {
      Instant expiresAt = row.getExpiresAt();
      if (expiresAt != null && !expiresAt.isAfter(now)) {
        return State.DEACTIVATED;
      }
      return switch (row.state(now)) {
        case ACTIVE -> State.USABLE;
        case INVITED -> State.INVITED;
        case EXPIRED -> State.DEACTIVATED;
        case LOCKED ->
            row.getLockedReason() == LockReason.FAILED_LOGINS
                ? State.LOCKED_OUT
                : State.DEACTIVATED;
      };
    }

    /** An account that never signed in counts from its creation. */
    private boolean isInactive(User user) {
      Instant lastActivity =
          user.getLastLoginAt() != null ? user.getLastLoginAt() : user.getCreatedAt();
      return lastActivity == null || lastActivity.isBefore(now.minus(inactivityThreshold));
    }
  }

  private static boolean isLocal(User user) {
    return LocalIssuer.URN.equals(user.getIssuer());
  }
}
