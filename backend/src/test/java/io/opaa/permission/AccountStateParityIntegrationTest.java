package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.auth.AccountUsability;
import io.opaa.auth.AccountUsability.State;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The database writes the account-state history by its own copy of the rule of {@link
 * AccountUsability} ({@code account_state_reconcile}); a difference between the two would cut or
 * keep access in the Stichtagsauskunft that the application decided otherwise. For a matrix of
 * account states - every lock reason, expiry before and after a lockout, invitation, the switch of
 * the local management, the system administration's exception, provider on, off and gone, issuer
 * spellings with slashes, blanks, control characters and other case, two rows of one issuer, a
 * handover - the history holds the account locked right now, with the cause that matches the rule's
 * state, exactly when the rule calls it unusable. Two deliberate exceptions: a failed-login lockout
 * leaves no interval, and the dev mode's issuer, which the database cannot know, counts as an
 * issuer without provider (this context runs in the oidc mode).
 */
@OpaaLocalAuthMockMvcTest
class AccountStateParityIntegrationTest {

  private static final String PREFIX = "https://idp.example/parity-it/";

  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private UserRepository users;
  @Autowired private AccountUsability usability;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private final List<UUID> accounts = new ArrayList<>();
  private final List<UUID> providers = new ArrayList<>();

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
  }

  @AfterEach
  void tearDown() {
    accounts.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    providers.forEach(id -> jdbc.update("DELETE FROM oidc_providers WHERE id = ?", id));
    jdbc.update(
        "DELETE FROM oidc_provider_removals WHERE issuer_uri_normalized LIKE ?", PREFIX + "%");
    fixtures.cleanUp();
    accounts.clear();
    providers.clear();
  }

  @Test
  void localAccountsWithTheManagementSwitchedOn() {
    fixtures.localProvider(true);
    List<Case> cases = new ArrayList<>();
    cases.add(
        new Case(
            "usable", local("USER", "now()", "NULL", "NULL", "NULL", "NULL"), null, State.USABLE));
    cases.add(
        new Case(
            "admin lock",
            local("USER", "now()", "now()", "'ADMIN'", "NULL", "NULL"),
            "ADMIN_LOCKED",
            State.DEACTIVATED));
    cases.add(
        new Case(
            "inactivity lock",
            local("USER", "now()", "now()", "'INACTIVITY'", "NULL", "NULL"),
            "INACTIVITY_LOCKED",
            State.DORMANT_INACTIVE));
    cases.add(
        new Case(
            "lockout in force, deliberately not recorded",
            local(
                "USER",
                "now()",
                "now()",
                "'FAILED_LOGINS'",
                "now() + interval '15 minutes'",
                "NULL"),
            null,
            State.LOCKED_OUT));
    cases.add(
        new Case(
            "lockout over",
            local(
                "USER",
                "now()",
                "now() - interval '1 hour'",
                "'FAILED_LOGINS'",
                "now() - interval '45 minutes'",
                "NULL"),
            null,
            State.USABLE));
    cases.add(
        new Case(
            "failed-login lock without an end",
            local("USER", "now()", "now()", "'FAILED_LOGINS'", "NULL", "NULL"),
            null,
            State.USABLE));
    cases.add(
        new Case(
            "expired",
            local("USER", "now()", "NULL", "NULL", "NULL", "now() - interval '1 day'"),
            "LOCAL_EXPIRED",
            State.DEACTIVATED));
    cases.add(
        new Case(
            "expiry ahead",
            local("USER", "now()", "NULL", "NULL", "NULL", "now() + interval '1 day'"),
            null,
            State.USABLE));
    cases.add(
        new Case(
            "expired during a lockout",
            local(
                "USER",
                "now()",
                "now()",
                "'FAILED_LOGINS'",
                "now() + interval '15 minutes'",
                "now() - interval '1 day'"),
            "LOCAL_EXPIRED",
            State.DEACTIVATED));
    cases.add(
        new Case(
            "expiry ahead of a lockout that is over",
            local(
                "USER",
                "now()",
                "now() - interval '1 hour'",
                "'FAILED_LOGINS'",
                "now() - interval '45 minutes'",
                "now() + interval '1 day'"),
            null,
            State.USABLE));
    cases.add(
        new Case(
            "invited",
            localWithoutPassword("now()", "NULL", "NULL"),
            "LOCAL_INVITED",
            State.INVITED));
    cases.add(
        new Case(
            "address unconfirmed",
            local("USER", "NULL", "NULL", "NULL", "NULL", "NULL"),
            "LOCAL_INVITED",
            State.INVITED));
    cases.add(
        new Case(
            "invited and locked",
            localWithoutPassword("now()", "now()", "'ADMIN'"),
            "ADMIN_LOCKED",
            State.DEACTIVATED));
    cases.add(
        new Case(
            "admin, usable",
            local("SYSTEM_ADMIN", "now()", "NULL", "NULL", "NULL", "NULL"),
            null,
            State.USABLE));
    UUID directoryLocked = local("USER", "now()", "NULL", "NULL", "NULL", "NULL");
    jdbc.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", directoryLocked);
    cases.add(new Case("directory lock", directoryLocked, "DIRECTORY_LOCKED", State.DEACTIVATED));

    assertEveryCaseAgrees(cases);
  }

  @Test
  void localAccountsWithTheManagementSwitchedOff() {
    fixtures.localProvider(false);
    List<Case> cases = new ArrayList<>();
    cases.add(
        new Case(
            "regular",
            local("USER", "now()", "NULL", "NULL", "NULL", "NULL"),
            "LOCAL_ACCOUNTS_DISABLED",
            State.DORMANT_LOCAL_ACCOUNTS_DISABLED));
    cases.add(
        new Case(
            "auditor",
            local("AUDITOR", "now()", "NULL", "NULL", "NULL", "NULL"),
            "LOCAL_ACCOUNTS_DISABLED",
            State.DORMANT_LOCAL_ACCOUNTS_DISABLED));
    cases.add(
        new Case(
            "admin",
            local("SYSTEM_ADMIN", "now()", "NULL", "NULL", "NULL", "NULL"),
            null,
            State.USABLE));
    cases.add(
        new Case(
            "admin expired",
            local("SYSTEM_ADMIN", "now()", "NULL", "NULL", "NULL", "now() - interval '1 day'"),
            "LOCAL_EXPIRED",
            State.DEACTIVATED));
    cases.add(
        new Case(
            "admin locked",
            local("SYSTEM_ADMIN", "now()", "now()", "'ADMIN'", "NULL", "NULL"),
            "ADMIN_LOCKED",
            State.DEACTIVATED));
    UUID invitedAdmin = localWithoutPassword("now()", "NULL", "NULL");
    jdbc.update("UPDATE users SET system_role = 'SYSTEM_ADMIN' WHERE id = ?", invitedAdmin);
    cases.add(new Case("admin invited", invitedAdmin, "LOCAL_INVITED", State.INVITED));

    assertEveryCaseAgrees(cases);
  }

  @Test
  void accountsOfSignInProviders() {
    String enabled = PREFIX + UUID.randomUUID() + "/realm";
    provider(enabled, true);
    String storedWithSlash = PREFIX + UUID.randomUUID() + "/realm";
    provider(storedWithSlash + "/", true);
    String disabled = PREFIX + UUID.randomUUID() + "/realm";
    provider(disabled, false);
    String removed = PREFIX + UUID.randomUUID() + "/realm";
    jdbc.update(
        "INSERT INTO oidc_provider_removals (issuer_uri_normalized, removed_at) VALUES (?, now())",
        removed);
    List<Case> cases = new ArrayList<>();
    cases.add(new Case("exact issuer", oidc(enabled), null, State.USABLE));
    cases.add(new Case("trailing slash", oidc(enabled + "/"), null, State.USABLE));
    cases.add(new Case("two trailing slashes", oidc(enabled + "//"), null, State.USABLE));
    cases.add(new Case("blanks around", oidc(" " + enabled + " "), null, State.USABLE));
    cases.add(
        new Case("control characters around", oidc("\t" + enabled + "/\n"), null, State.USABLE));
    cases.add(
        new Case(
            "blank before the slash", oidc(enabled + " /"), "PROVIDER_REMOVED", State.DEACTIVATED));
    cases.add(
        new Case("other case", oidc(enabled.toUpperCase()), "PROVIDER_REMOVED", State.DEACTIVATED));
    cases.add(new Case("provider stored with slash", oidc(storedWithSlash), null, State.USABLE));
    cases.add(
        new Case(
            "provider switched off",
            oidc(disabled),
            "PROVIDER_DISABLED",
            State.DORMANT_PROVIDER_DISABLED));
    cases.add(
        new Case("provider deleted", oidc(removed + "/"), "PROVIDER_REMOVED", State.DEACTIVATED));
    cases.add(
        new Case(
            "issuer without any provider",
            oidc(PREFIX + UUID.randomUUID()),
            "PROVIDER_REMOVED",
            State.DEACTIVATED));
    cases.add(
        new Case(
            "local issuer spelled with a slash",
            oidc(LocalIssuer.URN + "/"),
            "PROVIDER_REMOVED",
            State.DEACTIVATED));
    UUID directoryLocked = oidc(enabled);
    jdbc.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", directoryLocked);
    cases.add(new Case("directory lock", directoryLocked, "DIRECTORY_LOCKED", State.DEACTIVATED));

    assertEveryCaseAgrees(cases);
  }

  /**
   * Two provider rows whose issuers differ only by blanks - possible by hand in the database, not
   * through the administration, which trims - decide by the same row as {@link AccountUsability}:
   * the last in the order of sort number and display name.
   */
  @Test
  void ofTwoProvidersOfOneIssuerTheLastInTheRuleOrderDecides() {
    String issuer = PREFIX + UUID.randomUUID() + "/realm";
    rawProvider("A Anbieter", issuer, true, 0);
    rawProvider("B Anbieter", " " + issuer, false, 0);
    String other = PREFIX + UUID.randomUUID() + "/realm";
    rawProvider("Z Anbieter", other + " ", false, 1);
    rawProvider("A Anbieter", other, true, 2);

    assertEveryCaseAgrees(
        List.of(
            new Case(
                "the display name decides",
                oidc(issuer),
                "PROVIDER_DISABLED",
                State.DORMANT_PROVIDER_DISABLED),
            new Case("the sort number decides", oidc(other), null, State.USABLE)));
  }

  /** A local account handed over to a provider is judged by that provider from then on. */
  @Test
  void aHandedOverAccountIsJudgedByItsNewProvider() {
    fixtures.localProvider(true);
    String issuer = PREFIX + UUID.randomUUID() + "/realm";
    provider(issuer, true);
    UUID account = local("USER", "now()", "now()", "'ADMIN'", "NULL", "NULL");
    assertThat(lockedCauseNow(account)).isEqualTo("ADMIN_LOCKED");

    jdbc.update(
        "UPDATE users SET issuer = ?, subject = ? WHERE id = ?", issuer, "sub-" + account, account);
    jdbc.update("DELETE FROM local_credentials WHERE user_id = ?", account);

    assertEveryCaseAgrees(List.of(new Case("handed over", account, null, State.USABLE)));
  }

  // ---- helpers

  /** One account, the cause its open LOCKED interval must carry (none: usable), the rule state. */
  record Case(String name, UUID account, String cause, State state) {}

  private void assertEveryCaseAgrees(List<Case> cases) {
    List<String> disagreements = new ArrayList<>();
    for (Case each : cases) {
      jdbc.queryForObject(
          "SELECT count(*) FROM (SELECT account_state_reconcile(?, 'ACCOUNT_REACTIVATED', false))"
              + " AS evaluated",
          Integer.class,
          each.account());
      State rule = usability.stateOf(users.findById(each.account()).orElseThrow());
      String recorded = lockedCauseNow(each.account());
      if (rule != each.state() || !Objects.equals(recorded, each.cause())) {
        disagreements.add(
            each.name()
                + ": rule "
                + rule
                + " (expected "
                + each.state()
                + "), history "
                + recorded
                + " (expected "
                + each.cause()
                + ")");
      }
    }
    assertThat(disagreements).isEmpty();
  }

  /** The cause of the open LOCKED interval, or {@code null} for an account recorded usable. */
  private String lockedCauseNow(UUID userId) {
    List<String> causes =
        jdbc.queryForList(
            "SELECT cause FROM account_state_history WHERE user_id = ? AND valid_to IS NULL"
                + " AND state = 'LOCKED'",
            String.class,
            userId);
    return causes.isEmpty() ? null : causes.getFirst();
  }

  private UUID user(String issuer, String role) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, system_role,"
            + " organization_id) VALUES (?, ?, ?, ?, 'Parität', ?, ?)",
        id,
        id.toString(),
        issuer,
        "paritaet-" + id + "@stadt.example",
        role,
        Organization.DEFAULT_ID);
    accounts.add(id);
    return id;
  }

  private UUID oidc(String issuer) {
    return user(issuer, "USER");
  }

  private UUID local(
      String role,
      String verifiedAt,
      String lockedAt,
      String lockedReason,
      String lockoutUntil,
      String expiresAt) {
    return local(role, "'x'", verifiedAt, lockedAt, lockedReason, lockoutUntil, expiresAt);
  }

  private UUID localWithoutPassword(String verifiedAt, String lockedAt, String lockedReason) {
    return local("USER", "NULL", verifiedAt, lockedAt, lockedReason, "NULL", "NULL");
  }

  private UUID local(
      String role,
      String passwordHash,
      String verifiedAt,
      String lockedAt,
      String lockedReason,
      String lockoutUntil,
      String expiresAt) {
    UUID id = user(LocalIssuer.URN, role);
    jdbc.update(
        "INSERT INTO local_credentials (user_id, created_reason, password_hash, email_verified_at,"
            + " locked_at, locked_reason, lockout_until, expires_at) VALUES (?, 'Parität', "
            + String.join(
                ", ", passwordHash, verifiedAt, lockedAt, lockedReason, lockoutUntil, expiresAt)
            + ")",
        id);
    return id;
  }

  private void provider(String issuer, boolean enabled) {
    UUID id = fixtures.oidcProvider("Parität", issuer, "opaa-frontend").getId();
    providers.add(id);
    if (!enabled) {
      fixtures.disableProvider(id);
    }
  }

  private void rawProvider(String name, String issuer, boolean enabled, int sortOrder) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO oidc_providers (id, display_name, enabled, sort_order, issuer_uri, client_id)"
            + " VALUES (?, ?, ?, ?, ?, 'opaa-frontend')",
        id,
        name,
        enabled,
        sortOrder,
        issuer);
    providers.add(id);
  }
}
