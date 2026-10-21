package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.account.LocalUserCreation;
import io.opaa.account.LocalUserService;
import io.opaa.account.LocalUserUpdate;
import io.opaa.api.types.LockReason;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AccountUsability;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.OidcProvider;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.directory.OidcProviderService;
import io.opaa.organization.Organization;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every way an account becomes unusable or usable again leaves its interval in {@code
 * account_state_history} (ADR-0036, Entscheidung 8), in the transaction of the change and from the
 * database clock: a lock by the administration or for inactivity, an expiry set into the past or
 * reached by time, a failed-login lockout with the end it reaches by itself, an open invitation,
 * the switched-off local account management, a switched-off or deleted sign-in provider. After each
 * step the history agrees with {@link AccountUsability}, the one rule of a usable account.
 */
@OpaaLocalAuthMockMvcTest
class AccountStateHistoryWritePathsIntegrationTest {

  private static final String LOGIN = "/api/v1/auth/local/login";
  private static final String ISSUER_PREFIX = "https://idp.example/account-state-it/";

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalUserService localUsers;
  @Autowired private OidcProviderService providers;
  @Autowired private UserRepository users;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private AccountUsability usability;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private LocalAccount admin;
  private CurrentUser actor;
  private final List<UUID> oidcUsers = new ArrayList<>();
  private final List<UUID> oidcProviders = new ArrayList<>();

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    admin = fixtures.activeAdmin("verwaltung-" + UUID.randomUUID() + "@stadt.example");
    actor =
        CurrentUser.of(admin.id(), Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, "Verwaltung");
  }

  @AfterEach
  void tearDown() {
    for (UUID userId : oidcUsers) {
      jdbc.update("DELETE FROM account_state_history WHERE user_id = ?", userId);
      jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }
    for (UUID providerId : oidcProviders) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", providerId.toString());
      jdbc.update("DELETE FROM oidc_providers WHERE id = ?", providerId);
    }
    jdbc.update(
        "DELETE FROM oidc_provider_removals WHERE issuer_uri_normalized LIKE ?",
        ISSUER_PREFIX + "%");
    jdbc.update(
        "DELETE FROM account_state_history h USING users u WHERE u.id = h.user_id"
            + " AND u.issuer = 'urn:opaa:local'");
    fixtures.cleanUp();
    oidcUsers.clear();
    oidcProviders.clear();
  }

  @Test
  void anAdministratorsLockAndItsLiftingAreRecorded() {
    LocalAccount user = fixtures.activeUser(address());

    localUsers.lock(actor, user.id());

    assertThat(chainOf(user.id()))
        .extracting(Interval::state, Interval::cause, Interval::open)
        .containsExactly(
            tuple("ACTIVE", "ACCOUNT_CREATED", false), tuple("LOCKED", "ADMIN_LOCKED", true));
    assertAgreesWithTheUsabilityRule(user.id());

    localUsers.unlock(actor, user.id());

    List<Interval> chain = chainOf(user.id());
    assertThat(chain)
        .extracting(Interval::state, Interval::cause, Interval::open)
        .containsExactly(
            tuple("ACTIVE", "ACCOUNT_CREATED", false),
            tuple("LOCKED", "ADMIN_LOCKED", false),
            tuple("ACTIVE", "ACCOUNT_REACTIVATED", true));
    assertContiguous(chain);
    assertAgreesWithTheUsabilityRule(user.id());
  }

  @Test
  void aLockForInactivityIsRecorded() {
    LocalAccount user = fixtures.activeUser(address());

    assertThat(localUsers.lockForInactivity(user.id())).isTrue();

    assertThat(openInterval(user.id()))
        .extracting(Interval::state, Interval::cause)
        .containsExactly("LOCKED", "INACTIVITY_LOCKED");
    assertAgreesWithTheUsabilityRule(user.id());
  }

  @Test
  void anExpiryDateInThePastLocksAndTakingItBackReactivates() {
    LocalAccount user = fixtures.activeUser(address());

    localUsers.update(
        actor,
        user.id(),
        new LocalUserUpdate(
            null, null, null, Instant.now().minus(Duration.ofHours(1)), false, null));

    assertThat(openInterval(user.id()))
        .extracting(Interval::state, Interval::cause)
        .containsExactly("LOCKED", "LOCAL_EXPIRED");
    assertAgreesWithTheUsabilityRule(user.id());

    localUsers.update(actor, user.id(), new LocalUserUpdate(null, null, null, null, true, null));

    assertThat(openInterval(user.id()))
        .extracting(Interval::state, Interval::cause)
        .containsExactly("ACTIVE", "ACCOUNT_REACTIVATED");
    assertAgreesWithTheUsabilityRule(user.id());
  }

  /**
   * An expiry date that passes changes no row: the sweep records it, from a boundary of the
   * database clock that is not before the expiry - never earlier than the account really expired.
   */
  @Test
  void anExpiryReachedWithoutAnyWriteIsRecordedByTheSweep() {
    LocalAccount user = fixtures.activeUser(address());
    Instant expiry = databaseNow().plus(Duration.ofMillis(400));
    localUsers.update(actor, user.id(), new LocalUserUpdate(null, null, null, expiry, false, null));
    assertThat(chainOf(user.id())).as("not expired yet, nothing to record").isEmpty();

    waitForTheDatabaseClockToPass(expiry);
    jdbc.queryForObject("SELECT account_state_record_reached_expiries(100)", Integer.class);

    Interval open = openInterval(user.id());
    assertThat(open.state()).isEqualTo("LOCKED");
    assertThat(open.cause()).isEqualTo("LOCAL_EXPIRED");
    assertThat(open.validFrom()).isAfterOrEqualTo(expiry);
    assertAgreesWithTheUsabilityRule(user.id());

    jdbc.queryForObject("SELECT account_state_record_reached_expiries(100)", Integer.class);
    assertThat(chainOf(user.id())).as("a second sweep records nothing again").hasSize(2);
  }

  /**
   * A failed-login lockout ends by itself and is caused by whoever types the address: it leaves no
   * interval, however often it recurs - neither a trail of failed passwords nor a history that
   * outgrows the Stichtagsauskunft's bound. The auskunft names the person during it.
   */
  @Test
  void loginLockoutsLeaveNoIntervalHoweverOftenTheyRecur() throws Exception {
    LocalAccount user = fixtures.activeUser(address());

    for (int cycle = 0; cycle < 3; cycle++) {
      for (int i = 0; i < 5; i++) {
        login(user.email(), "falsches-passwort");
      }
      LocalCredentials lockedOut = credentials.findById(user.id()).orElseThrow();
      assertThat(lockedOut.getLockedReason()).isEqualTo(LockReason.FAILED_LOGINS);
      assertThat(usability.stateOf(users.findById(user.id()).orElseThrow()))
          .isEqualTo(AccountUsability.State.LOCKED_OUT);
      // the lockout runs out without any write; a past end stands in for the elapsed time
      Instant lockedAt = Instant.now().minus(Duration.ofMinutes(20));
      lockedOut.lock(LockReason.FAILED_LOGINS, lockedAt, lockedAt.plus(Duration.ofMinutes(15)));
      fixtures.save(lockedOut);
    }

    assertThat(chainOf(user.id())).isEmpty();
  }

  @Test
  void anOpenInvitationIsRecordedAsLockedUntilThePasswordIsSet() {
    UUID invited =
        localUsers
            .create(
                actor,
                new LocalUserCreation(
                    address(), "Eingeladen", SystemRole.USER, null, true, "Neueinstellung", true))
            .user()
            .getId();

    assertThat(chainOf(invited))
        .as("a new account starts locked - no active period before it was ever usable")
        .extracting(Interval::state, Interval::cause, Interval::open)
        .containsExactly(tuple("LOCKED", "LOCAL_INVITED", true));
    assertAgreesWithTheUsabilityRule(invited);

    localUsers.generatePassword(actor, invited);

    assertThat(chainOf(invited))
        .extracting(Interval::state, Interval::cause, Interval::open)
        .containsExactly(
            tuple("LOCKED", "LOCAL_INVITED", false), tuple("ACTIVE", "ACCOUNT_REACTIVATED", true));
    assertAgreesWithTheUsabilityRule(invited);
  }

  @Test
  void anAccountCreatedUsableHasNoInterval() {
    UUID created =
        localUsers
            .create(
                actor,
                new LocalUserCreation(
                    address(), "Sofort", SystemRole.USER, null, true, "Neueinstellung", false))
            .user()
            .getId();

    assertThat(chainOf(created)).isEmpty();
    assertAgreesWithTheUsabilityRule(created);
  }

  @Test
  void switchingOffTheLocalAccountsLocksTheRegularOnesButNotTheAdministration() {
    LocalAccount regular = fixtures.activeUser(address());

    providers.setLocalAccountsEnabled(Organization.DEFAULT_ID, admin.id(), false);

    assertThat(openInterval(regular.id()))
        .extracting(Interval::state, Interval::cause)
        .containsExactly("LOCKED", "LOCAL_ACCOUNTS_DISABLED");
    assertThat(chainOf(admin.id())).as("a local SYSTEM_ADMIN keeps signing in").isEmpty();
    assertAgreesWithTheUsabilityRule(regular.id());
    assertAgreesWithTheUsabilityRule(admin.id());

    providers.setLocalAccountsEnabled(Organization.DEFAULT_ID, admin.id(), true);

    assertThat(openInterval(regular.id()))
        .extracting(Interval::state, Interval::cause)
        .containsExactly("ACTIVE", "ACCOUNT_REACTIVATED");
    assertAgreesWithTheUsabilityRule(regular.id());
  }

  @Test
  void aSwitchedOffProviderLocksItsAccountsUntilItIsSwitchedOnAgain() {
    String issuer = ISSUER_PREFIX + UUID.randomUUID();
    OidcProvider provider = provider(issuer);
    UUID person = oidcUser(issuer);

    providers.setEnabled(Organization.DEFAULT_ID, admin.id(), provider.getId(), false, true, true);

    assertThat(openInterval(person))
        .extracting(Interval::state, Interval::cause)
        .containsExactly("LOCKED", "PROVIDER_DISABLED");
    assertThat(chainOf(admin.id())).as("an account of another issuer is untouched").isEmpty();
    assertAgreesWithTheUsabilityRule(person);

    providers.setEnabled(Organization.DEFAULT_ID, admin.id(), provider.getId(), true, true, true);

    assertThat(openInterval(person))
        .extracting(Interval::state, Interval::cause)
        .containsExactly("ACTIVE", "ACCOUNT_REACTIVATED");
    assertAgreesWithTheUsabilityRule(person);
  }

  @Test
  void aDeletedProviderLocksItsAccountsUntilAProviderOfTheSameIssuerExistsAgain() {
    String issuer = ISSUER_PREFIX + UUID.randomUUID();
    OidcProvider provider = provider(issuer);
    UUID person = oidcUser(issuer);

    providers.deleteProvider(Organization.DEFAULT_ID, admin.id(), provider.getId(), true, true);
    oidcProviders.remove(provider.getId());

    assertThat(openInterval(person))
        .extracting(Interval::state, Interval::cause)
        .containsExactly("LOCKED", "PROVIDER_REMOVED");
    assertAgreesWithTheUsabilityRule(person);

    provider(issuer + "/");

    assertThat(openInterval(person))
        .as("the issuer is compared without its trailing slash")
        .extracting(Interval::state, Interval::cause)
        .containsExactly("ACTIVE", "ACCOUNT_REACTIVATED");
    assertAgreesWithTheUsabilityRule(person);
  }

  @Test
  void aSignInRecordsNothing() throws Exception {
    LocalAccount user = fixtures.activeUser(address());

    login(user.email(), LocalAccountFixtures.PASSWORD).andExpect(status().isOk());
    login(user.email(), "falsches-passwort");

    assertThat(chainOf(user.id())).isEmpty();
  }

  /**
   * The intervals of an account alone name no access - every period they could cut stands in
   * another history, whose person column keeps refusing the deletion - so a once locked account
   * stays deletable.
   */
  @Test
  void anAccountThatWasLockedCanStillBeDeleted() {
    LocalAccount user = fixtures.activeUser(address());
    localUsers.lock(actor, user.id());
    localUsers.unlock(actor, user.id());

    localUsers.delete(actor, user.id());

    assertThat(users.findById(user.id())).isEmpty();
    assertThat(chainOf(user.id())).isEmpty();
  }

  // ---- helpers

  private OidcProvider provider(String issuer) {
    OidcProvider provider = fixtures.oidcProvider("Anbieter", issuer, "opaa-frontend");
    oidcProviders.add(provider.getId());
    return provider;
  }

  private UUID oidcUser(String issuer) {
    User user = new User(UUID.randomUUID().toString(), issuer, address(), "Person");
    user.setOrganizationId(Organization.DEFAULT_ID);
    UUID id = users.save(user).getId();
    oidcUsers.add(id);
    return id;
  }

  private org.springframework.test.web.servlet.ResultActions login(String email, String password)
      throws Exception {
    return mockMvc.perform(
        post(LOGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
  }

  private static String address() {
    return "person-" + UUID.randomUUID() + "@stadt.example";
  }

  record Interval(String state, String cause, Instant validFrom, Instant validTo) {
    boolean open() {
      return validTo == null;
    }
  }

  private List<Interval> chainOf(UUID userId) {
    return jdbc.query(
        "SELECT state, cause, valid_from, valid_to FROM account_state_history WHERE user_id = ?"
            + " ORDER BY valid_from, valid_to NULLS LAST",
        (rs, row) ->
            new Interval(
                rs.getString("state"),
                rs.getString("cause"),
                rs.getTimestamp("valid_from").toInstant(),
                rs.getTimestamp("valid_to") == null
                    ? null
                    : rs.getTimestamp("valid_to").toInstant()),
        userId);
  }

  private Interval openInterval(UUID userId) {
    List<Interval> open = chainOf(userId).stream().filter(Interval::open).toList();
    assertThat(open).as("exactly one open interval").hasSize(1);
    return open.getFirst();
  }

  private static void assertContiguous(List<Interval> chain) {
    for (int i = 1; i < chain.size(); i++) {
      assertThat(chain.get(i).validFrom()).isEqualTo(chain.get(i - 1).validTo());
    }
  }

  /**
   * The history holds the account locked right now exactly when the rule calls it unusable - an
   * account without any row counts as never locked.
   */
  private void assertAgreesWithTheUsabilityRule(UUID userId) {
    Boolean lockedNow =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM account_state_history WHERE user_id = ?"
                + " AND valid_to IS NULL AND state = 'LOCKED')",
            Boolean.class,
            userId);
    User user = users.findById(userId).orElseThrow();
    assertThat(lockedNow)
        .as("locked in the history <=> not usable by AccountUsability")
        .isEqualTo(!usability.stateOf(user).isUsable());
  }

  private Instant databaseNow() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }

  /** Waits on the database clock itself, the clock the sweep compares the expiry with. */
  private void waitForTheDatabaseClockToPass(Instant instant) {
    jdbc.queryForObject(
        "SELECT pg_sleep(greatest(0, extract(epoch FROM (? - clock_timestamp())) + 0.05))",
        Object.class,
        Timestamp.from(instant));
    assertThat(databaseNow()).isAfter(instant);
  }
}
