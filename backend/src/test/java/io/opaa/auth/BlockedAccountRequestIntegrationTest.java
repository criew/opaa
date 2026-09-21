package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.oidc.OidcProvider;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OidcProviderTokens;
import io.opaa.test.OpaaLocalAuthProviderTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The first acceptance criterion of #1818, through the production {@code oidc} chain: an account
 * the directory run has blocked cannot make a request any more - <b>not even with the token it
 * already holds</b>, which is still perfectly valid and which OPAA cannot revoke at the provider.
 * The refusal names its reason in {@code WWW-Authenticate}, the way every other ended session does
 * (ADR-0033, Entscheidung 8), so the sign-in page can tell the person what happened and whom to ask
 * instead of showing a bare "Zugang verweigert" (Personalrat C3).
 */
@OpaaLocalAuthProviderTest
class BlockedAccountRequestIntegrationTest {

  private static final String ME = "/api/v1/auth/me";
  private static final String ISSUER = "https://idp.test.example/realms/beschaeftigte";
  private static final String CLIENT_ID = "opaa-frontend";

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private OidcProviderTokens providerTokens;
  @Autowired private UserRepository users;
  @Autowired private AccountStateService accountState;
  @Autowired private AccountStateHistoryRepository accountStateHistory;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private OidcProvider provider;
  private final List<UUID> providerAccounts = new ArrayList<>();

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    provider = fixtures.oidcProvider("Beschäftigte", ISSUER, CLIENT_ID);
  }

  @AfterEach
  void tearDown() {
    if (!providerAccounts.isEmpty()) {
      accountStateHistory.deleteByUserIdIn(providerAccounts);
      jdbc.update(
          "DELETE FROM audit_log WHERE event_type IN ('ACCOUNT_DEACTIVATED','ACCOUNT_REACTIVATED')");
      providerAccounts.forEach(fixtures::deleteAccount);
      providerAccounts.clear();
    }
    fixtures.cleanUp();
    fixtures.deleteProvider(provider.getId());
  }

  @Test
  void aBlockedAccountIsRefusedWithItsReasonEvenWithAStillValidProviderToken() throws Exception {
    String subject = "erika-" + UUID.randomUUID();
    String token = providerTokens.token(ISSUER, subject);

    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").isNotEmpty());
    User account = users.findBySubjectAndIssuer(subject, ISSUER).orElseThrow();
    providerAccounts.add(account.getId());

    accountState.apply(
        account,
        AccountStateHistoryCause.DIRECTORY_ABSENT,
        provider.getId(),
        null,
        UUID.randomUUID().toString());

    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    HttpHeaders.WWW_AUTHENTICATE,
                    containsString(UserProvisioningFilter.ACCOUNT_BLOCKED_MARKER)));
  }

  /** The reversal reaches the very next request - the same token works again. */
  @Test
  void aReactivatedAccountIsLetBackInWithTheSameToken() throws Exception {
    String subject = "grace-" + UUID.randomUUID();
    String token = providerTokens.token(ISSUER, subject);
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk());
    User account = users.findBySubjectAndIssuer(subject, ISSUER).orElseThrow();
    providerAccounts.add(account.getId());
    String correlationRef = UUID.randomUUID().toString();
    accountState.apply(
        account,
        AccountStateHistoryCause.DIRECTORY_DISABLED,
        provider.getId(),
        null,
        correlationRef);
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized());

    accountState.apply(
        account,
        AccountStateHistoryCause.DIRECTORY_REACTIVATED,
        provider.getId(),
        null,
        correlationRef);

    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk());
    assertThat(accountState.isBlocked(account.getId())).isFalse();
  }
}
