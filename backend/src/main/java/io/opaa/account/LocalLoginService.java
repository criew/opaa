package io.opaa.account;

import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalAccountAccess;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * Verifies the credentials of a local sign-in (ADR-0033, Entscheidungen 1, 3, 4 and 9). The address
 * is matched case-insensitively among local accounts only. Exactly one {@link
 * PasswordEncoder#matches} runs per attempt - against {@link #DUMMY_HASH} when there is no account
 * or no password - so the response-time class never tells an unknown address from a wrong password.
 * Only an {@code ACTIVE} account signs in, and with the management switched off only a local {@code
 * SYSTEM_ADMIN}; every refusal is the same empty result. A wrong password of a known account is
 * logged once at INFO with the account id, then counted atomically and reported to every {@link
 * LocalLoginAttemptListener} - unless the account is locked, then nothing is counted; a successful
 * sign-in resets the counter. Account state comes from {@link LocalCredentials#state} alone - a
 * lock's stale {@code locked_at} after a lockout expired is not a lock. A local {@code
 * SYSTEM_ADMIN} signing in from outside {@link LocalAdminNetworkPolicy}'s networks is refused like
 * a wrong password - before the counter, so the account cannot be locked from there, and without
 * the listeners (ADR-0033, Entscheidung 9); the hash comparison still runs so the timing stays
 * alike.
 *
 * <p>The row read before the hash comparison only selects the hash. What the comparison decides is
 * settled afterwards on the row re-read under its row lock ({@code PESSIMISTIC_WRITE}): a lock set
 * by a concurrent attempt meanwhile turns a right password into the same refusal as a wrong one and
 * stops the count, so parallel attempts beyond the threshold in force reveal and count nothing.
 */
@Service
public class LocalLoginService {

  /**
   * A BCrypt cost-12 hash of a value nobody knows, behind the delegating encoder's {@code {bcrypt}}
   * id: comparing against it costs exactly what a real comparison costs.
   */
  static final String DUMMY_HASH =
      "{bcrypt}$2a$12$vuBcpQelR7dqUorH5Avrpe4i.bXGwt2/1XUXmrNjTYRnv3MTAdwSS";

  private static final Logger log = LoggerFactory.getLogger(LocalLoginService.class);

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final PasswordEncoder passwordEncoder;
  private final OidcProviderRegistry registry;
  private final List<LocalLoginAttemptListener> listeners;
  private final LocalAdminNetworkPolicy networkPolicy;
  private final Clock clock;
  private final TransactionOperations transactions;

  @Autowired
  public LocalLoginService(
      UserRepository users,
      LocalCredentialsRepository credentials,
      PasswordEncoder passwordEncoder,
      OidcProviderRegistry registry,
      List<LocalLoginAttemptListener> listeners,
      LocalAdminNetworkPolicy networkPolicy,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this(
        users,
        credentials,
        passwordEncoder,
        registry,
        listeners,
        networkPolicy,
        clock,
        new TransactionTemplate(transactionManager));
  }

  LocalLoginService(
      UserRepository users,
      LocalCredentialsRepository credentials,
      PasswordEncoder passwordEncoder,
      OidcProviderRegistry registry,
      List<LocalLoginAttemptListener> listeners,
      LocalAdminNetworkPolicy networkPolicy,
      Clock clock,
      TransactionOperations transactions) {
    this.users = users;
    this.credentials = credentials;
    this.passwordEncoder = passwordEncoder;
    this.registry = registry;
    this.listeners = List.copyOf(listeners);
    this.networkPolicy = networkPolicy;
    this.clock = clock;
    this.transactions = transactions;
  }

  /**
   * The account when the sign-in is accepted; empty for every refusal alike. {@code clientAddress}
   * is the resolved client address ({@code io.opaa.security.ClientIpResolver}), consulted for local
   * {@code SYSTEM_ADMIN} accounts only. Must be called without a surrounding transaction: the
   * decision commits in a short transaction of its own, so that its row lock is never held across
   * the hash comparison or the caller's further work.
   */
  public Optional<AuthenticatedLocalAccount> authenticate(
      String email, String password, String clientAddress) {
    Assert.state(
        !TransactionSynchronizationManager.isActualTransactionActive(),
        "LocalLoginService#authenticate must not run inside a transaction");
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
    // the network gate comes before the count: an administrator's account must not be lockable
    // by failed attempts from a network it can never sign in from (ADR-0033, Entscheidung 9)
    if (user.getSystemRole() == SystemRole.SYSTEM_ADMIN
        && !networkPolicy.permitsAdminSignIn(clientAddress)) {
      log.warn(
          "Sign-in of local SYSTEM_ADMIN account {} refused: client address outside"
              + " OPAA_LOCAL_ADMIN_ALLOWED_CIDRS",
          user.getId());
      return Optional.empty();
    }
    if (!passwordMatches) {
      // the one log line of a failed attempt (ADR-0033, Entscheidung 9): account id, never the
      // address, whether or not the attempt is counted
      log.info("Local sign-in of account {} refused: wrong password", user.getId());
      // already locked when read: nothing to count, and no row lock taken during a lockout flood
      if (row.state(now) != LocalAccountState.LOCKED) {
        transactions.executeWithoutResult(status -> countFailedAttempt(user, now));
      }
      return Optional.empty();
    }
    if (!LocalAccountAccess.isLoginCapable(row, now)
        || !LocalAccountAccess.passesManagementSwitch(registry, user)) {
      return Optional.empty();
    }
    LocalCredentials current = transactions.execute(status -> admitSignIn(user, row, now));
    if (current == null) {
      return Optional.empty();
    }
    listeners.forEach(listener -> listener.onLoginSucceeded(user, current, now));
    return Optional.of(new AuthenticatedLocalAccount(user, current));
  }

  /**
   * Counts a wrong password on the locked row. A locked account counts nothing: attempts during a
   * lockout neither extend it nor spend the fresh budget the account has once the lockout has
   * ended. The listeners run under the same row lock, so exactly one attempt reaches the threshold.
   */
  private void countFailedAttempt(User user, Instant now) {
    LocalCredentials locked = credentials.findForUpdate(user.getId()).orElse(null);
    if (locked == null || locked.state(now) == LocalAccountState.LOCKED) {
      return;
    }
    credentials.recordFailedLogin(user.getId(), now);
    LocalCredentials counted = credentials.findById(user.getId()).orElse(locked);
    listeners.forEach(listener -> listener.onPasswordRejected(user, counted, now));
  }

  /**
   * The row a verified password signs in with, or null: re-read under its lock, it must still be
   * login-capable and still carry the hash that was compared - a lock or a new password set while
   * the comparison ran refuses the sign-in like a wrong password.
   */
  private LocalCredentials admitSignIn(User user, LocalCredentials compared, Instant now) {
    LocalCredentials locked = credentials.findForUpdate(user.getId()).orElse(null);
    if (locked == null
        || !Objects.equals(locked.getPasswordHash(), compared.getPasswordHash())
        || !LocalAccountAccess.isLoginCapable(locked, now)) {
      return null;
    }
    if (locked.getFailedLoginAttempts() != 0) {
      credentials.resetFailedLoginAttempts(user.getId(), now);
    }
    return locked;
  }

  /** A local account whose password was just verified. */
  public record AuthenticatedLocalAccount(User user, LocalCredentials credentials) {}
}
