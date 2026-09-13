package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.LockReason;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.organization.Organization;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OidcProviderTokens;
import io.opaa.test.OpaaLocalAuthProviderTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The handover of a local account to a provider identity through the production {@code oidc} chain
 * (ADR-0033, Entscheidung 12): the administration starts it naming a provider and a reason and
 * never an identity, the person sees what moves, redeems it with a token of that provider - the
 * subject comes from that token alone - and signs in afterwards through the ordinary provisioning
 * path, with no special case anywhere. The refusals are the point of the feature: an administrator
 * alone cannot take over an identity, an identity that already has an account is never merged, the
 * emergency-anchor account is excluded outright, and the last login-capable system administrator is
 * kept at both ends of the flow.
 *
 * <p>This context has no public base URL, so every handover link is handed to the administrator
 * (LINK_DISPLAYED) - which is also how the tests get hold of the raw code.
 */
@OpaaLocalAuthProviderTest
class LocalHandoverIntegrationTest {

  private static final String LOGIN = "/api/v1/auth/local/login";
  private static final String ME = "/api/v1/auth/me";
  private static final String LOCAL_USERS = "/api/v1/admin/local-users";
  private static final String PREVIEW = "/api/v1/auth/local/handover/preview";
  private static final String REDEEM = "/api/v1/auth/local/handover/redeem";
  private static final String ISSUER = "https://idp.test.example/realms/beschaeftigte";
  private static final String CLIENT_ID = "opaa-frontend";
  private static final String REASON = "Umstellung auf den Identitätsanbieter der Stadt";
  private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private OidcProviderTokens providerTokens;
  @Autowired private UserRepository users;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private LocalActionTokenService actionTokens;
  @Autowired private LocalActionTokenRepository actionTokenRepository;
  @Autowired private LocalRefreshTokenRepository refreshTokens;
  @Autowired private AuditEventRecorder audit;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private LocalAccount admin;
  private String adminBearer;
  private OidcProvider provider;
  private final List<UUID> foreignAccounts = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    actionTokenRepository.deleteAll();
    deleteLocalAuditRows();
    fixtures.localProvider(true);
    provider = fixtures.oidcProvider("Beschäftigte", ISSUER, CLIENT_ID);
    admin = fixtures.activeAdmin("verwaltung-" + UUID.randomUUID() + "@stadt.example");
    adminBearer = bearer(login(admin.email(), LocalAccountFixtures.PASSWORD, 200));
  }

  @AfterEach
  void tearDown() {
    foreignAccounts.forEach(fixtures::deleteAccount);
    foreignAccounts.clear();
    actionTokenRepository.deleteAll();
    deleteLocalAuditRows();
    fixtures.cleanUp();
    fixtures.deleteProvider(provider.getId());
  }

  @Test
  void theAdministrationStartsAHandoverNamingAProviderAndAReasonButNeverAnIdentity()
      throws Exception {
    LocalAccount person = fixtures.activeUser(address("erika"));
    Instant before = Instant.now();

    MvcResult started = startHandover(person.id(), provider.getId(), REASON, 200);
    String body = started.getResponse().getContentAsString();

    assertThat(JsonPath.<Boolean>read(body, "$.emailSent")).isFalse();
    assertThat(JsonPath.<String>read(body, "$.deliveryPath")).isEqualTo("LINK_DISPLAYED");
    String url = JsonPath.read(body, "$.handoverUrl");
    assertThat(url).startsWith("/" + LocalAccountLinks.HANDOVER_PATH + "?token=");

    LocalActionToken token =
        actionTokens.findRedeemable(tokenIn(url), ActionTokenPurpose.HANDOVER).orElseThrow();
    assertThat(token.getUserId()).isEqualTo(person.id());
    assertThat(token.getProviderId()).isEqualTo(provider.getId());
    assertThat(token.getReason()).isEqualTo(REASON);
    assertThat(token.getExpiresAt())
        .isAfter(before.plus(Duration.ofHours(71)))
        .isBefore(before.plus(Duration.ofHours(73)));

    // the identity is untouched and the account stays usable until the person redeems
    User unchanged = users.findById(person.id()).orElseThrow();
    assertThat(unchanged.getIssuer()).isEqualTo(LocalIssuer.URN);
    assertThat(unchanged.getSubject()).isEqualTo(person.id().toString());
    login(person.email(), LocalAccountFixtures.PASSWORD, 200);

    List<Map<String, Object>> requested = auditRows("LOCAL_USER_HANDOVER_REQUESTED", person.id());
    assertThat(requested).hasSize(1);
    String after = (String) requested.getFirst().get("after");
    assertThat(after).contains("LINK_DISPLAYED").contains(provider.getId().toString());
    assertThat(String.valueOf(requested.getFirst().values()))
        .doesNotContain(person.email())
        .doesNotContain(LocalAccountFixtures.DISPLAY_NAME)
        .doesNotContain(REASON)
        .doesNotContain(person.id().toString());
  }

  @Test
  void thePreviewShowsWhatMovesAndLeavesTheCodeRedeemable() throws Exception {
    LocalAccount person = fixtures.activeUser(address("erika"));
    // the personal space is provisioned on the first request of the account
    login(person.email(), LocalAccountFixtures.PASSWORD, 200);
    String code = tokenIn(startHandover(person.id(), provider.getId(), REASON, 200));

    mockMvc
        .perform(json(post(PREVIEW), "{\"token\":\"" + code + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value(LocalAccountFixtures.DISPLAY_NAME))
        .andExpect(jsonPath("$.reason").value(REASON))
        .andExpect(jsonPath("$.provider.id").value(provider.getId().toString()))
        .andExpect(jsonPath("$.provider.displayName").value("Beschäftigte"))
        .andExpect(jsonPath("$.scope.systemRole").value("USER"))
        .andExpect(jsonPath("$.scope.spaceMemberships").isNumber())
        .andExpect(jsonPath("$.scope.groupMemberships").value(0))
        .andExpect(jsonPath("$.expiresAt").isNotEmpty());

    // a preview consumes nothing: the code is still there, twice over
    mockMvc.perform(json(post(PREVIEW), "{\"token\":\"" + code + "\"}")).andExpect(status().isOk());
    assertThat(actionTokens.findRedeemable(code, ActionTokenPurpose.HANDOVER)).isPresent();
  }

  /**
   * The whole point of Entscheidung 12: after the redemption the account is the same row under a
   * new identity, and the next sign-in through the provider finds it over the ordinary key - no
   * special case in the provisioner, and no second account.
   */
  @Test
  void theRedemptionMovesTheAccountAndTheNextProviderSignInFindsItOverTheOrdinaryKey()
      throws Exception {
    LocalAccount person = fixtures.activeUser(address("erika"));
    MvcResult signedIn = login(person.email(), LocalAccountFixtures.PASSWORD, 200);
    String localBearer = bearer(signedIn);
    String code = tokenIn(startHandover(person.id(), provider.getId(), REASON, 200));
    String subject = "erika-" + UUID.randomUUID();
    long accountsBefore = users.count();

    mockMvc
        .perform(json(post(REDEEM), redeemBody(code, providerTokens.token(ISSUER, subject))))
        .andExpect(status().isNoContent());

    foreignAccounts.add(person.id());
    User handedOver = users.findById(person.id()).orElseThrow();
    assertThat(handedOver.getIssuer()).isEqualTo(ISSUER);
    assertThat(handedOver.getSubject()).isEqualTo(subject);
    assertThat(handedOver.getSystemRole()).isEqualTo(SystemRole.USER);
    assertThat(credentials.findById(person.id())).isEmpty();
    assertThat(actionTokenRepository.findAll()).noneMatch(t -> t.getUserId().equals(person.id()));
    assertThat(refreshTokens.findAll())
        .filteredOn(t -> t.getUserId().equals(person.id()))
        .allMatch(t -> t.getRevocationReason() == RevocationReason.HANDED_OVER);
    assertThat(users.count()).isEqualTo(accountsBefore);

    // the token left in another tab says why it stopped working (ADR-0033, Entscheidung 8)
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, localBearer))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header().string("WWW-Authenticate", containsString("session_revoked:handed_over")));

    // and the provider's own token now resolves to that very account
    mockMvc
        .perform(
            get(ME)
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + providerTokens.token(ISSUER, subject)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(person.id().toString()));
    assertThat(users.count()).isEqualTo(accountsBefore);

    List<Map<String, Object>> handed = auditRows("LOCAL_USER_HANDED_OVER", person.id());
    assertThat(handed).hasSize(1);
    String after = (String) handed.getFirst().get("after");
    assertThat(after).contains(provider.getId().toString()).contains("spaceMemberships");
    assertThat(String.valueOf(handed.getFirst().values()))
        .doesNotContain(subject)
        .doesNotContain(person.email())
        .doesNotContain(LocalAccountFixtures.DISPLAY_NAME)
        .doesNotContain(person.id().toString());
  }

  /**
   * An administrator alone can start a handover and nothing else: the request body has no field for
   * an identity, and the redemption refuses everything the administrator can produce on their own -
   * a token of the local issuer belongs to another issuer than the handover names, and a made-up
   * one is not a token at all.
   */
  @Test
  void anAdministratorAloneCannotTakeOverAnIdentity() throws Exception {
    LocalAccount person = fixtures.activeUser(address("erika"));
    String code = tokenIn(startHandover(person.id(), provider.getId(), REASON, 200));

    mockMvc
        .perform(json(post(REDEEM), redeemBody(code, adminBearer.substring("Bearer ".length()))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(LocalHandoverAccountService.PROVIDER_MISMATCH));
    mockMvc
        .perform(json(post(REDEEM), redeemBody(code, "kein-token")))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            json(
                post(REDEEM),
                redeemBody(code, providerTokens.token(ISSUER, "fremd", Duration.ofMinutes(-5)))))
        .andExpect(status().isUnauthorized());

    User unchanged = users.findById(person.id()).orElseThrow();
    assertThat(unchanged.getIssuer()).isEqualTo(LocalIssuer.URN);
    assertThat(credentials.findById(person.id())).isPresent();
    assertThat(actionTokens.findRedeemable(code, ActionTokenPurpose.HANDOVER)).isPresent();
  }

  /** ADR-0025's hard line: two accounts stay two accounts, and the code stays unspent. */
  @Test
  void anIdentityThatAlreadyHasAnAccountIsNeverMerged() throws Exception {
    LocalAccount person = fixtures.activeUser(address("erika"));
    String code = tokenIn(startHandover(person.id(), provider.getId(), REASON, 200));
    String subject = "erika-" + UUID.randomUUID();
    User existing = new User(subject, ISSUER, address("zweit"), "Erika Zweitkonto");
    existing.setOrganizationId(Organization.DEFAULT_ID);
    foreignAccounts.add(fixtures.save(existing).getId());

    mockMvc
        .perform(json(post(REDEEM), redeemBody(code, providerTokens.token(ISSUER, subject))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(LocalHandoverAccountService.PROVIDER_ACCOUNT_EXISTS));

    assertThat(users.findById(person.id()).orElseThrow().getIssuer()).isEqualTo(LocalIssuer.URN);
    assertThat(credentials.findById(person.id())).isPresent();
    // the refusal rolled the consumption back with it - the code is still the person's way out
    assertThat(actionTokens.findRedeemable(code, ActionTokenPurpose.HANDOVER)).isPresent();
  }

  @Test
  void everyRefusedCodeLooksTheSame() throws Exception {
    LocalAccount person = fixtures.activeUser(address("erika"));
    String code = tokenIn(startHandover(person.id(), provider.getId(), REASON, 200));
    String subject = "erika-" + UUID.randomUUID();
    // consumed: the first redemption succeeds, the second finds nothing
    mockMvc
        .perform(json(post(REDEEM), redeemBody(code, providerTokens.token(ISSUER, subject))))
        .andExpect(status().isNoContent());
    foreignAccounts.add(person.id());

    for (String refused :
        List.of(
            code, "unbekannter-code", invitationCodeOf(fixtures.invitedUser(address("otto"))))) {
      mockMvc
          .perform(json(post(PREVIEW), "{\"token\":\"" + refused + "\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(LocalActionTokenService.TOKEN_INVALID));
      mockMvc
          .perform(json(post(REDEEM), redeemBody(refused, providerTokens.token(ISSUER, "x"))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(LocalActionTokenService.TOKEN_INVALID));
    }
  }

  /**
   * The row that carries lock and expiry is the very row a redemption deletes, so a handover that
   * skipped this gate would lift an administrative lock, an inactivity lock or an expiry date on
   * its way through - weeks after an administrator set it, and with the system role intact. Both
   * ends of the code are gated, and both answer the one refusal every unusable link answers.
   */
  @Test
  void aCodeOfAnAccountThatMayNoLongerRedeemIsAsInvalidAsAnyOther() throws Exception {
    LocalAccount locked = fixtures.activeUser(address("gesperrt"));
    String lockedCode = tokenIn(startHandover(locked.id(), provider.getId(), REASON, 200));
    LocalCredentials lockedRow = fixtures.credentialsOf(locked);
    lockedRow.lock(LockReason.ADMIN, Instant.now(), null);
    fixtures.save(lockedRow);

    LocalAccount expired = fixtures.activeUser(address("abgelaufen"));
    String expiredCode = tokenIn(startHandover(expired.id(), provider.getId(), REASON, 200));
    LocalCredentials expiredRow = fixtures.credentialsOf(expired);
    expiredRow.setExpiresAt(Instant.now().minusSeconds(60), Instant.now());
    fixtures.save(expiredRow);

    for (String code : List.of(lockedCode, expiredCode)) {
      mockMvc
          .perform(json(post(PREVIEW), "{\"token\":\"" + code + "\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(LocalActionTokenService.TOKEN_INVALID));
      mockMvc
          .perform(
              json(post(REDEEM), redeemBody(code, providerTokens.token(ISSUER, "sub-" + code))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(LocalActionTokenService.TOKEN_INVALID));
    }

    // both accounts are untouched: still local, still credentialed, still in the state they were
    assertThat(users.findById(locked.id()).orElseThrow().getIssuer()).isEqualTo(LocalIssuer.URN);
    assertThat(users.findById(expired.id()).orElseThrow().getIssuer()).isEqualTo(LocalIssuer.URN);
    assertThat(credentials.findById(locked.id()).orElseThrow().getLockedAt()).isNotNull();
    assertThat(credentials.findById(expired.id()).orElseThrow().getExpiresAt()).isNotNull();
  }

  /**
   * The state machine lets a lock outrank the expiry, so an expired account in a failed-login
   * lockout reports {@code LOCKED} with {@code FAILED_LOGINS} - the one state a link may still be
   * redeemed in. Five wrong passwords of the person would otherwise have reopened the handover for
   * a quarter of an hour, and a redeemed handover deletes the expiry date with the row that carries
   * it. The expiry is therefore read on its own; the temporary lockout keeps its meaning.
   */
  @Test
  void aFailedLoginLockoutDoesNotReopenTheCodeOfAnExpiredAccount() throws Exception {
    Instant now = Instant.now();
    LocalAccount expired = fixtures.activeUser(address("abgelaufen"));
    String expiredCode = tokenIn(startHandover(expired.id(), provider.getId(), REASON, 200));
    LocalCredentials expiredRow = fixtures.credentialsOf(expired);
    expiredRow.setExpiresAt(now.minusSeconds(60), now);
    expiredRow.lock(LockReason.FAILED_LOGINS, now, now.plusSeconds(900));
    fixtures.save(expiredRow);

    mockMvc
        .perform(json(post(PREVIEW), "{\"token\":\"" + expiredCode + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(LocalActionTokenService.TOKEN_INVALID));
    mockMvc
        .perform(
            json(post(REDEEM), redeemBody(expiredCode, providerTokens.token(ISSUER, "abgelaufen"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(LocalActionTokenService.TOKEN_INVALID));
    assertThat(users.findById(expired.id()).orElseThrow().getIssuer()).isEqualTo(LocalIssuer.URN);
    assertThat(credentials.findById(expired.id()).orElseThrow().getExpiresAt()).isNotNull();
  }

  /** The other direction of the same rule: a lockout alone is no reason to refuse the code. */
  @Test
  void aFailedLoginLockoutAloneLeavesTheCodeRedeemable() throws Exception {
    Instant now = Instant.now();
    LocalAccount person = fixtures.activeUser(address("gesperrt-nach-fehlversuchen"));
    String code = tokenIn(startHandover(person.id(), provider.getId(), REASON, 200));
    LocalCredentials row = fixtures.credentialsOf(person);
    row.lock(LockReason.FAILED_LOGINS, now, now.plusSeconds(900));
    fixtures.save(row);

    mockMvc.perform(json(post(PREVIEW), "{\"token\":\"" + code + "\"}")).andExpect(status().isOk());
    String subject = "erika-" + UUID.randomUUID();
    mockMvc
        .perform(json(post(REDEEM), redeemBody(code, providerTokens.token(ISSUER, subject))))
        .andExpect(status().isNoContent());
    foreignAccounts.add(person.id());
    assertThat(users.findById(person.id()).orElseThrow().getSubject()).isEqualTo(subject);
  }

  /**
   * An act of the administration closes the open handover code, exactly as it closes an invitation
   * or a reset link: after a changed address the code went to the old one, and after a lock or a
   * generated password it would carry the person past the act.
   */
  @Test
  void anAdministrativeActClosesTheOpenHandoverCode() throws Exception {
    record Act(String name, java.util.function.Consumer<UUID> apply) {}
    List<Act> acts =
        List.of(
            new Act(
                "address change",
                id ->
                    perform(
                        patch(LOCAL_USERS + "/" + id),
                        "{\"email\":\"neu-" + id + "@stadt.example\"}",
                        200)),
            new Act("lock", id -> perform(post(LOCAL_USERS + "/" + id + "/lock"), null, 200)),
            new Act(
                "generated password",
                id -> perform(post(LOCAL_USERS + "/" + id + "/password"), null, 200)));

    for (Act act : acts) {
      LocalAccount person = fixtures.activeUser(address("erika"));
      String code = tokenIn(startHandover(person.id(), provider.getId(), REASON, 200));
      assertThat(actionTokens.findRedeemable(code, ActionTokenPurpose.HANDOVER))
          .as("before the %s", act.name())
          .isPresent();

      act.apply().accept(person.id());

      assertThat(actionTokens.findRedeemable(code, ActionTokenPurpose.HANDOVER))
          .as("the %s left the handover code open", act.name())
          .isEmpty();
      mockMvc
          .perform(json(post(PREVIEW), "{\"token\":\"" + code + "\"}"))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void theEmergencyAnchorAccountIsNeverHandedOver() throws Exception {
    LocalAccount anchor = fixtures.activeAdmin(address("notanker"));
    LocalCredentials row = fixtures.credentialsOf(anchor);
    row.markBootstrap();
    fixtures.save(row);

    mockMvc
        .perform(
            json(
                    post(LOCAL_USERS + "/" + anchor.id() + "/handover"),
                    handoverBody(provider.getId(), REASON))
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(LocalUserService.BOOTSTRAP_ACCOUNT));
    assertThat(actionTokenRepository.findAll()).noneMatch(t -> t.getUserId().equals(anchor.id()));
  }

  /**
   * The emergency-anchor branch of the <em>redemption</em>: the account can become the emergency
   * anchor after a code was issued, and the redemption refuses it on its own rather than trusting
   * that the request already did.
   */
  @Test
  void anAccountThatBecameTheEmergencyAnchorIsNotHandedOverEither() throws Exception {
    LocalAccount person = fixtures.activeAdmin(address("spaeterer-notanker"));
    fixtures.activeAdmin(address("zweite"));
    String code = tokenIn(startHandover(person.id(), provider.getId(), REASON, 200));
    LocalCredentials row = fixtures.credentialsOf(person);
    row.markBootstrap();
    fixtures.save(row);

    mockMvc
        .perform(json(post(REDEEM), redeemBody(code, providerTokens.token(ISSUER, "notanker-sub"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(LocalUserService.BOOTSTRAP_ACCOUNT));

    assertThat(users.findById(person.id()).orElseThrow().getIssuer()).isEqualTo(LocalIssuer.URN);
    assertThat(credentials.findById(person.id())).isPresent();
  }

  /**
   * The guard of Entscheidung 4 at both ends: weeks may pass between the two, and it is the
   * redemption that actually takes the local administrator away.
   */
  @Test
  void theLastLoginCapableAdministratorIsKeptWhenStartingAndWhenRedeeming() throws Exception {
    LocalAccount second = fixtures.activeAdmin(address("zweite"));

    // the acting administrator is the only other one: handing the second one over is fine
    String code = tokenIn(startHandover(second.id(), provider.getId(), REASON, 200));

    // ... until the acting administrator stops being login-capable in the meantime
    LocalCredentials actingRow = fixtures.credentialsOf(admin);
    actingRow.lock(LockReason.ADMIN, Instant.now(), null);
    fixtures.save(actingRow);

    mockMvc
        .perform(json(post(REDEEM), redeemBody(code, providerTokens.token(ISSUER, "zweite-sub"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(LocalAdminAvailabilityGuard.ERROR_CODE));
    assertThat(users.findById(second.id()).orElseThrow().getIssuer()).isEqualTo(LocalIssuer.URN);

    // and starting one for the now only login-capable administrator is refused outright
    LocalCredentials unlocked = fixtures.credentialsOf(admin);
    unlocked.unlock(Instant.now());
    fixtures.save(unlocked);
    LocalCredentials secondRow = fixtures.credentialsOf(second);
    secondRow.lock(LockReason.ADMIN, Instant.now(), null);
    fixtures.save(secondRow);
    startHandover(admin.id(), provider.getId(), REASON, 409);
  }

  @Test
  void onlyAnEnabledOidcProviderCanBeChosen() throws Exception {
    LocalAccount person = fixtures.activeUser(address("erika"));
    OidcProvider disabled = fixtures.oidcProvider("Alt", ISSUER + "-alt", CLIENT_ID);
    try {
      fixtures.disableProvider(disabled.getId());
      startHandover(person.id(), disabled.getId(), REASON, 400);
      startHandover(person.id(), UUID.randomUUID(), REASON, 400);
      // the LOCAL row is a provider row, but never one an account can be handed over to
      UUID localRow = fixtures.localProvider(true).getId();
      startHandover(person.id(), localRow, REASON, 400);
      // and the reason is mandatory
      startHandover(person.id(), provider.getId(), "   ", 400);
      assertThat(actionTokenRepository.findAll()).isEmpty();
    } finally {
      fixtures.deleteProvider(disabled.getId());
    }
  }

  @Test
  void onlyASystemAdministratorMayStartAHandover() throws Exception {
    LocalAccount person = fixtures.activeUser(address("erika"));
    String personBearer = bearer(login(person.email(), LocalAccountFixtures.PASSWORD, 200));
    mockMvc
        .perform(
            json(
                    post(LOCAL_USERS + "/" + person.id() + "/handover"),
                    handoverBody(provider.getId(), REASON))
                .header(HttpHeaders.AUTHORIZATION, personBearer))
        .andExpect(status().isForbidden());
    assertThat(actionTokenRepository.findAll()).isEmpty();
  }

  // ---- helpers

  private String address(String prefix) {
    return prefix + "-" + UUID.randomUUID() + "@stadt.example";
  }

  private MvcResult startHandover(UUID userId, UUID providerId, String reason, int expectedStatus)
      throws Exception {
    return mockMvc
        .perform(
            json(post(LOCAL_USERS + "/" + userId + "/handover"), handoverBody(providerId, reason))
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
        .andExpect(status().is(expectedStatus))
        .andReturn();
  }

  /** Runs an administrative call and asserts its status; the table above needs a void action. */
  private void perform(MockHttpServletRequestBuilder request, String body, int expectedStatus) {
    try {
      mockMvc
          .perform(
              (body == null ? request : json(request, body))
                  .header(HttpHeaders.AUTHORIZATION, adminBearer))
          .andExpect(status().is(expectedStatus));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String handoverBody(UUID providerId, String reason) {
    return "{\"providerId\":\"" + providerId + "\",\"reason\":\"" + reason + "\"}";
  }

  private static String redeemBody(String code, String providerToken) {
    return "{\"token\":\"" + code + "\",\"providerToken\":\"" + providerToken + "\"}";
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private String invitationCodeOf(LocalAccount invited) throws Exception {
    MvcResult reset =
        mockMvc
            .perform(
                post(LOCAL_USERS + "/" + invited.id() + "/password-reset")
                    .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk())
            .andReturn();
    return tokenIn(JsonPath.<String>read(reset.getResponse().getContentAsString(), "$.setupUrl"));
  }

  private MvcResult login(String email, String password, int expectedStatus) throws Exception {
    return mockMvc
        .perform(
            json(post(LOGIN), "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().is(expectedStatus))
        .andReturn();
  }

  private static String bearer(MvcResult result) throws Exception {
    return "Bearer " + JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }

  private static String tokenIn(MvcResult result) throws Exception {
    return tokenIn(
        JsonPath.<String>read(result.getResponse().getContentAsString(), "$.handoverUrl"));
  }

  private static String tokenIn(String text) {
    Matcher matcher = TOKEN_IN_LINK.matcher(text);
    assertThat(matcher.find()).as("link with token in: %s", text).isTrue();
    return matcher.group(1);
  }

  private List<Map<String, Object>> auditRows(String eventType, UUID userId) {
    String pseudonym = audit.pseudonymFor(userId, Organization.DEFAULT_ID).toString();
    return jdbc.queryForList(
        "SELECT event_type, actor_kind, actor_ref, object_label, subject_ref,"
            + " CAST(before AS text) AS before, CAST(after AS text) AS after, reason"
            + " FROM audit_log WHERE event_type = ? AND subject_ref = ? ORDER BY recorded_at",
        eventType,
        pseudonym);
  }

  private void deleteLocalAuditRows() {
    jdbc.update(
        "DELETE FROM audit_log WHERE event_type LIKE 'LOCAL_%'"
            + " OR (event_type = 'SPACE_DELETED' AND organization_id = ?)",
        Organization.DEFAULT_ID);
  }
}
