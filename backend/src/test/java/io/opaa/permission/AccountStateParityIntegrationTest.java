package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.auth.AccountUsability;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * spellings with slashes, blanks, control characters and other case - the history holds an account
 * locked right now exactly when the rule calls it unusable.
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
    Map<String, UUID> cases = new LinkedHashMap<>();
    cases.put("usable", local("USER", "now()", "NULL", "NULL", "NULL", "NULL"));
    cases.put("admin lock", local("USER", "now()", "now()", "'ADMIN'", "NULL", "NULL"));
    cases.put("inactivity lock", local("USER", "now()", "now()", "'INACTIVITY'", "NULL", "NULL"));
    cases.put(
        "lockout in force",
        local(
            "USER", "now()", "now()", "'FAILED_LOGINS'", "now() + interval '15 minutes'", "NULL"));
    cases.put(
        "lockout over",
        local(
            "USER",
            "now()",
            "now() - interval '1 hour'",
            "'FAILED_LOGINS'",
            "now() - interval '45 minutes'",
            "NULL"));
    cases.put(
        "expired", local("USER", "now()", "NULL", "NULL", "NULL", "now() - interval '1 day'"));
    cases.put(
        "expiry ahead", local("USER", "now()", "NULL", "NULL", "NULL", "now() + interval '1 day'"));
    cases.put(
        "expired during a lockout",
        local(
            "USER",
            "now()",
            "now()",
            "'FAILED_LOGINS'",
            "now() + interval '15 minutes'",
            "now() - interval '1 day'"));
    cases.put(
        "expiry ahead of a lockout that is over",
        local(
            "USER",
            "now()",
            "now() - interval '1 hour'",
            "'FAILED_LOGINS'",
            "now() - interval '45 minutes'",
            "now() + interval '1 day'"));
    cases.put("invited", localWithoutPassword("now()", "NULL", "NULL"));
    cases.put("address unconfirmed", local("USER", "NULL", "NULL", "NULL", "NULL", "NULL"));
    cases.put("invited and locked", localWithoutPassword("now()", "now()", "'ADMIN'"));
    cases.put("admin, usable", local("SYSTEM_ADMIN", "now()", "NULL", "NULL", "NULL", "NULL"));
    UUID directoryLocked = local("USER", "now()", "NULL", "NULL", "NULL", "NULL");
    jdbc.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", directoryLocked);
    cases.put("directory lock", directoryLocked);

    assertEveryCaseAgrees(cases);
  }

  @Test
  void localAccountsWithTheManagementSwitchedOff() {
    fixtures.localProvider(false);
    Map<String, UUID> cases = new LinkedHashMap<>();
    cases.put("regular", local("USER", "now()", "NULL", "NULL", "NULL", "NULL"));
    cases.put("auditor", local("AUDITOR", "now()", "NULL", "NULL", "NULL", "NULL"));
    cases.put("admin", local("SYSTEM_ADMIN", "now()", "NULL", "NULL", "NULL", "NULL"));
    cases.put(
        "admin expired",
        local("SYSTEM_ADMIN", "now()", "NULL", "NULL", "NULL", "now() - interval '1 day'"));
    cases.put("admin locked", local("SYSTEM_ADMIN", "now()", "now()", "'ADMIN'", "NULL", "NULL"));
    cases.put("admin invited", localWithoutPassword("now()", "NULL", "NULL"));
    jdbc.update(
        "UPDATE users SET system_role = 'SYSTEM_ADMIN' WHERE id = ?", cases.get("admin invited"));

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
    Map<String, UUID> cases = new LinkedHashMap<>();
    cases.put("exact issuer", oidc(enabled));
    cases.put("trailing slash", oidc(enabled + "/"));
    cases.put("two trailing slashes", oidc(enabled + "//"));
    cases.put("blanks around", oidc(" " + enabled + " "));
    cases.put("control characters around", oidc("\t" + enabled + "/\n"));
    cases.put("blank before the slash", oidc(enabled + " /"));
    cases.put("other case", oidc(enabled.toUpperCase()));
    cases.put("provider stored with slash", oidc(storedWithSlash));
    cases.put("provider switched off", oidc(disabled));
    cases.put("provider deleted", oidc(removed + "/"));
    cases.put("issuer without any provider", oidc(PREFIX + UUID.randomUUID()));
    cases.put("local issuer spelled with a slash", oidc(LocalIssuer.URN + "/"));
    UUID directoryLocked = oidc(enabled);
    jdbc.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", directoryLocked);
    cases.put("directory lock", directoryLocked);

    assertEveryCaseAgrees(cases);
  }

  // ---- helpers

  private void assertEveryCaseAgrees(Map<String, UUID> cases) {
    List<String> disagreements = new ArrayList<>();
    int usable = 0;
    for (Map.Entry<String, UUID> each : cases.entrySet()) {
      UUID id = each.getValue();
      jdbc.queryForObject(
          "SELECT count(*) FROM (SELECT account_state_reconcile(?, 'ACCOUNT_REACTIVATED', false))"
              + " AS evaluated",
          Integer.class,
          id);
      User user = users.findById(id).orElseThrow();
      boolean ruleUsable = usability.stateOf(user).isUsable();
      boolean historyLocked = lockedNow(id);
      usable += ruleUsable ? 1 : 0;
      if (historyLocked == ruleUsable) {
        disagreements.add(
            each.getKey()
                + ": rule "
                + usability.stateOf(user)
                + ", history locked "
                + historyLocked);
      }
    }
    assertThat(disagreements).isEmpty();
    assertThat(usable)
        .as("the matrix holds usable and unusable accounts")
        .isBetween(1, cases.size() - 1);
  }

  private boolean lockedNow(UUID userId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM account_state_history WHERE user_id = ?"
                + " AND valid_to IS NULL AND state = 'LOCKED'"
                + " AND (locked_until IS NULL OR locked_until > clock_timestamp()))",
            Boolean.class,
            userId));
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
}
