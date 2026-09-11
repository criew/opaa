package io.opaa.test;

import io.opaa.api.types.PasswordChangeReason;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.local.LocalCredentials;
import io.opaa.auth.local.LocalCredentialsRepository;
import io.opaa.auth.local.LocalRefreshTokenRepository;
import io.opaa.auth.local.LocalRevokedTokenRepository;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.auth.oidc.OidcProvidersChangedEvent;
import io.opaa.organization.Organization;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test data of the local account management (ADR-0033) for integration tests, shared by #1533 and
 * the sub-issues after it: the one LOCAL provider row (created switched on or off), local accounts
 * with an encoded password in every derived state, and the cleanup that removes all of it again. A
 * plain helper over the repositories, not a Spring bean, so it never changes a context's cache key
 * (AGENTS.md, Spring-Testkontexte).
 *
 * <p>The provider row is written inside a committed transaction that publishes {@link
 * OidcProvidersChangedEvent}, because {@code OidcProviderRegistry} learns the switch's state only
 * from that event after the commit - exactly the path {@code OidcProviderService#setEnabled} takes.
 */
public final class LocalAccountFixtures {

  public static final String PASSWORD = "korrekt-batterie-pferd-klammer";
  public static final String DISPLAY_NAME = "Erika Muster";

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final LocalRefreshTokenRepository refreshTokens;
  private final LocalRevokedTokenRepository revokedTokens;
  private final OidcProviderRepository providers;
  private final PasswordEncoder passwordEncoder;
  private final TransactionTemplate transactions;
  private final ApplicationEventPublisher events;
  private final JdbcTemplate jdbc;

  public LocalAccountFixtures(
      UserRepository users,
      LocalCredentialsRepository credentials,
      LocalRefreshTokenRepository refreshTokens,
      LocalRevokedTokenRepository revokedTokens,
      OidcProviderRepository providers,
      PasswordEncoder passwordEncoder,
      TransactionTemplate transactions,
      ApplicationEventPublisher events,
      JdbcTemplate jdbc) {
    this.users = users;
    this.credentials = credentials;
    this.refreshTokens = refreshTokens;
    this.revokedTokens = revokedTokens;
    this.providers = providers;
    this.passwordEncoder = passwordEncoder;
    this.transactions = transactions;
    this.events = events;
    this.jdbc = jdbc;
  }

  /** Creates or updates the LOCAL provider row with the given switch state and commits it. */
  public OidcProvider localProvider(boolean enabled) {
    return transactions.execute(
        status -> {
          OidcProvider row =
              providers.findAll().stream()
                  .filter(OidcProvider::isLocal)
                  .findFirst()
                  .orElseGet(() -> OidcProvider.localProvider("Lokale Konten"));
          if (enabled) {
            row.enable();
          } else {
            row.disable();
          }
          OidcProvider saved = providers.save(row);
          events.publishEvent(new OidcProvidersChangedEvent());
          return saved;
        });
  }

  /** An {@code ACTIVE} regular local account with {@link #PASSWORD}. */
  public LocalAccount activeUser(String email) {
    return account(email, SystemRole.USER, PASSWORD, true, null);
  }

  /** An {@code ACTIVE} local {@code SYSTEM_ADMIN} with {@link #PASSWORD}. */
  public LocalAccount activeAdmin(String email) {
    return account(email, SystemRole.SYSTEM_ADMIN, PASSWORD, true, null);
  }

  /** An {@code ACTIVE} account that must change its password first. */
  public LocalAccount userWithForcedPasswordChange(String email, PasswordChangeReason reason) {
    return account(email, SystemRole.USER, PASSWORD, true, reason);
  }

  /** An {@code INVITED} account: address confirmed, but no password yet. */
  public LocalAccount invitedUser(String email) {
    return account(email, SystemRole.USER, null, true, null);
  }

  public LocalAccount account(
      String email,
      SystemRole role,
      String password,
      boolean emailVerified,
      PasswordChangeReason forcedChange) {
    Instant now = Instant.now();
    User user = User.localAccount(email, DISPLAY_NAME);
    user.setOrganizationId(Organization.DEFAULT_ID);
    user.setSystemRole(role);
    User savedUser = users.save(user);
    LocalCredentials row = new LocalCredentials(savedUser.getId(), "Testkonto", now);
    if (password != null) {
      row.setPasswordHash(passwordEncoder.encode(password), now);
    }
    if (emailVerified) {
      row.markEmailVerified(now);
    }
    if (forcedChange != null) {
      row.requirePasswordChange(forcedChange, now);
    }
    return new LocalAccount(savedUser, credentials.save(row));
  }

  /** Reloads the credentials row of {@code account} - the entities here are detached. */
  public LocalCredentials credentialsOf(LocalAccount account) {
    return credentials.findById(account.user().getId()).orElseThrow();
  }

  public User save(User user) {
    return users.save(user);
  }

  public LocalCredentials save(LocalCredentials row) {
    return credentials.save(row);
  }

  /**
   * Removes every local account (with the personal space its first request provisioned), every
   * local token row and the LOCAL provider row.
   */
  public void cleanUp() {
    transactions.executeWithoutResult(
        status -> {
          refreshTokens.deleteAll();
          revokedTokens.deleteAll();
          users.findAll().stream()
              .filter(u -> LocalIssuer.URN.equals(u.getIssuer()))
              .forEach(
                  u -> {
                    jdbc.update("DELETE FROM spaces WHERE owner_id = ?", u.getId());
                    credentials.deleteById(u.getId());
                    users.delete(u);
                  });
          providers.findAll().stream().filter(OidcProvider::isLocal).forEach(providers::delete);
          events.publishEvent(new OidcProvidersChangedEvent());
        });
  }

  /** A local account as the two rows it consists of. */
  public record LocalAccount(User user, LocalCredentials credentials) {
    public UUID id() {
      return user.getId();
    }

    public String email() {
      return user.getEmail();
    }
  }
}
