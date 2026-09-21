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
import io.opaa.auth.oidc.OidcClaimMapping;
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

  private static final String LOCAL_ACCOUNT_PSEUDONYMS =
      "SELECT CAST(p.pseudonym_id AS text) FROM audit_actor_pseudonyms p JOIN users u ON u.id ="
          + " p.user_id WHERE u.issuer = '"
          + LocalIssuer.URN
          + "'";

  /**
   * An {@code audit_log} condition: the row names a local account as actor, object or subject.
   * {@link #cleanUp()} removes every local account in both hooks, so these are the running class's
   * own rows - as long as the account still exists; a test that deletes one itself scopes by the
   * pseudonym it read beforehand.
   */
  public static final String NAMES_A_LOCAL_ACCOUNT =
      "(actor_ref IN ("
          + LOCAL_ACCOUNT_PSEUDONYMS
          + ") OR object_id IN ("
          + LOCAL_ACCOUNT_PSEUDONYMS
          + ") OR subject_ref IN ("
          + LOCAL_ACCOUNT_PSEUDONYMS
          + "))";

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

  /**
   * An enabled OIDC provider row, committed with the change event the registry listens for - the
   * counterpart of {@link #localProvider} for the handover tests (#1563). Written straight through
   * the repository on purpose: {@code OidcProviderService} would probe the issuer, and a test
   * issuer resolves nowhere. The JWK set address is set for the same reason - without it the
   * registry runs discovery against that address the moment it builds the decoder; with it, the
   * decoder is built without a single outbound call.
   */
  public OidcProvider oidcProvider(String displayName, String issuerUri, String clientId) {
    return transactions.execute(
        status -> {
          OidcProvider row =
              new OidcProvider(
                  displayName,
                  issuerUri,
                  clientId,
                  issuerUri + "/protocol/openid-connect/certs",
                  OidcClaimMapping.keycloakDefaults());
          row.enable();
          OidcProvider saved = providers.save(row);
          events.publishEvent(new OidcProvidersChangedEvent());
          return saved;
        });
  }

  /** Removes an account whatever its issuer - a handed-over one is no longer a local one. */
  public void deleteAccount(UUID userId) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "DELETE FROM audit_log a USING audit_actor_pseudonyms p WHERE p.user_id = ? AND"
                  + " CAST(p.pseudonym_id AS text) IN (a.actor_ref, a.object_id, a.subject_ref)",
              userId);
          jdbc.update("DELETE FROM local_action_tokens WHERE user_id = ?", userId);
          jdbc.update("DELETE FROM local_refresh_tokens WHERE user_id = ?", userId);
          jdbc.update("DELETE FROM local_revoked_tokens WHERE user_id = ?", userId);
          jdbc.update("DELETE FROM spaces WHERE owner_id = ?", userId);
          // #1815: see cleanUp() - the rights history of the space survives the space itself.
          jdbc.update("DELETE FROM space_membership_history WHERE subject_user_id = ?", userId);
          jdbc.update("DELETE FROM asset_ownership_history WHERE owner_user_id = ?", userId);
          credentials.deleteById(userId);
          users.findById(userId).ifPresent(users::delete);
        });
  }

  /**
   * The audit rows naming one person as actor, object or subject - for an account the test deleted
   * itself, whose pseudonym mapping went with it.
   */
  public void deleteAuditRowsNaming(UUID pseudonym) {
    jdbc.update(
        "DELETE FROM audit_log WHERE ? IN (actor_ref, object_id, subject_ref)",
        pseudonym.toString());
  }

  /** Switches an existing provider row off and tells the registry. */
  public void disableProvider(UUID providerId) {
    transactions.executeWithoutResult(
        status -> {
          providers
              .findById(providerId)
              .ifPresent(
                  row -> {
                    row.disable();
                    providers.save(row);
                  });
          events.publishEvent(new OidcProvidersChangedEvent());
        });
  }

  /** Removes an OIDC provider row again and tells the registry. */
  public void deleteProvider(UUID providerId) {
    transactions.executeWithoutResult(
        status -> {
          providers.findById(providerId).ifPresent(providers::delete);
          events.publishEvent(new OidcProvidersChangedEvent());
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
   * Removes every local account (with the personal space its first request provisioned and the
   * audit rows naming it), every local token row and the LOCAL provider row.
   */
  public void cleanUp() {
    transactions.executeWithoutResult(
        status -> {
          // Before the accounts: their pseudonyms cascade with them, and with them the only link.
          jdbc.update("DELETE FROM audit_log WHERE " + NAMES_A_LOCAL_ACCOUNT);
          refreshTokens.deleteAll();
          revokedTokens.deleteAll();
          users.findAll().stream()
              .filter(u -> LocalIssuer.URN.equals(u.getIssuer()))
              .forEach(
                  u -> {
                    jdbc.update("DELETE FROM spaces WHERE owner_id = ?", u.getId());
                    // #1815: the two rights-history tables carry no foreign key to their space
                    // (ADR-0016), so deleting the spaces above leaves their intervals behind -
                    // and their person columns are ON DELETE RESTRICT.
                    jdbc.update(
                        "DELETE FROM space_membership_history WHERE subject_user_id = ?",
                        u.getId());
                    jdbc.update(
                        "DELETE FROM asset_ownership_history WHERE owner_user_id = ?", u.getId());
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
