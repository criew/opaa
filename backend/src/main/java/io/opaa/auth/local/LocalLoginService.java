package io.opaa.auth.local;

import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProviderRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Verifies the credentials of a local sign-in (ADR-0033, Entscheidungen 1, 3, 4 and 9). The address
 * is matched case-insensitively among local accounts only. Exactly one {@link
 * PasswordEncoder#matches} runs per attempt - against {@link #DUMMY_HASH} when there is no account
 * or no password - so the response-time class never tells an unknown address from a wrong password.
 * Only an {@code ACTIVE} account signs in, and with the management switched off only a local {@code
 * SYSTEM_ADMIN}; every refusal is the same empty result. A wrong password of a known account is
 * counted atomically and reported to every {@link LocalLoginAttemptListener}; a successful sign-in
 * resets the counter. Account state comes from {@link LocalCredentials#state} alone - a lock's
 * stale {@code locked_at} after a lockout expired is not a lock.
 */
@Service
public class LocalLoginService {

  /**
   * A BCrypt cost-12 hash of a value nobody knows, behind the delegating encoder's {@code {bcrypt}}
   * id: comparing against it costs exactly what a real comparison costs.
   */
  static final String DUMMY_HASH =
      "{bcrypt}$2a$12$vuBcpQelR7dqUorH5Avrpe4i.bXGwt2/1XUXmrNjTYRnv3MTAdwSS";

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final PasswordEncoder passwordEncoder;
  private final OidcProviderRegistry registry;
  private final List<LocalLoginAttemptListener> listeners;
  private final Clock clock;

  public LocalLoginService(
      UserRepository users,
      LocalCredentialsRepository credentials,
      PasswordEncoder passwordEncoder,
      OidcProviderRegistry registry,
      List<LocalLoginAttemptListener> listeners,
      Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.passwordEncoder = passwordEncoder;
    this.registry = registry;
    this.listeners = List.copyOf(listeners);
    this.clock = clock;
  }

  /** The account when the sign-in is accepted; empty for every refusal alike. */
  public Optional<AuthenticatedLocalAccount> authenticate(String email, String password) {
    String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty() || password == null || password.isEmpty()) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    User user = users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, normalized).orElse(null);
    LocalCredentials row = user == null ? null : credentials.findById(user.getId()).orElse(null);
    String hash = row == null || row.getPasswordHash() == null ? DUMMY_HASH : row.getPasswordHash();
    boolean passwordMatches = passwordEncoder.matches(password, hash);
    if (row == null || row.getPasswordHash() == null) {
      return Optional.empty();
    }
    if (!passwordMatches) {
      credentials.recordFailedLogin(user.getId(), now);
      LocalCredentials counted = credentials.findById(user.getId()).orElse(row);
      listeners.forEach(listener -> listener.onPasswordRejected(user, counted, now));
      return Optional.empty();
    }
    if (!LocalAccountAccess.isLoginCapable(row, now)
        || !LocalAccountAccess.passesManagementSwitch(registry, user)) {
      return Optional.empty();
    }
    if (row.getFailedLoginAttempts() != 0) {
      credentials.resetFailedLoginAttempts(user.getId(), now);
    }
    listeners.forEach(listener -> listener.onLoginSucceeded(user, row, now));
    return Optional.of(new AuthenticatedLocalAccount(user, row));
  }

  /** A local account whose password was just verified. */
  public record AuthenticatedLocalAccount(User user, LocalCredentials credentials) {}
}
