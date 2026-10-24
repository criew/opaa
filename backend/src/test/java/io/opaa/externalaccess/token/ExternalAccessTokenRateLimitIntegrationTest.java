package io.opaa.externalaccess.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.AssetOwnerType;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.externalaccess.ExternalAccessSettings;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.knowledge.SourceType;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryCreation;
import io.opaa.library.LibraryExternalAccessService;
import io.opaa.test.GlobalCeilingWindow;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthRateLimitTest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The search budgets for a call with an access token, in front of the production chains: the token
 * has a budget of its own, apart from the one of its account, and spends none of the budget of its
 * address - behind one address a script whose token quota is used up would otherwise lock out
 * everyone else there. Limits from {@link OpaaLocalAuthRateLimitTest}: search three per client,
 * four per account and four for all tokens of one person together, five globally, per minute. The
 * addresses are this class's own.
 */
@OpaaLocalAuthRateLimitTest
class ExternalAccessTokenRateLimitIntegrationTest {

  private static final String SHARED_ADDRESS = "192.0.2.10";

  /** The channel admits only networks it names; the settings row is restored after each method. */
  private static final String CHANNEL_NETWORK = "192.0.2.0/24";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private Environment environment;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private LibraryExternalAccessService libraryRelease;
  @Autowired private ExternalAccessSettingsService settings;
  @Autowired private ExternalAccessTokenService tokenService;
  @Autowired private Clock clock;

  private LocalAccountFixtures fixtures;
  private LocalAccount anna;
  private UUID libraryId;
  private String tokenValue;
  private User administrator;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    resetSearchCeiling();
    administrator = fixtures.activeAdmin("rate-admin-" + UUID.randomUUID() + "@x.de").user();
    openChannel(settings.current().values().tokenRateLimitPerHour());
    anna = fixtures.activeUser("rate-token-" + UUID.randomUUID() + "@stadt.example");
    CurrentUser annaAsCaller = callerOf(anna.user());
    libraryId =
        libraryService
            .createLibrary(
                new LibraryCreation(
                    "Grenzen-Testbibliothek",
                    null,
                    AssetOwnerType.USER,
                    anna.id(),
                    SourceType.UPLOAD,
                    null,
                    null,
                    null,
                    null,
                    null),
                annaAsCaller)
            .library()
            .getId();
    libraryRelease.setExternalAccess(
        annaAsCaller, libraryId, true, clock.instant().plus(Duration.ofDays(60)));
    tokenValue = issueToken("Skript");
  }

  private String issueToken(String name) {
    return tokenService
        .issue(
            anna.id(),
            anna.user().getOrganizationId(),
            name,
            List.of(libraryId),
            clock.instant().plus(Duration.ofDays(30)))
        .rawValue();
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM external_access_tokens WHERE user_id = ?", anna.id());
    removeLibrary();
    fixtures.cleanUp();
    resetSearchCeiling();
  }

  @Test
  void aTokenSpendsNeitherTheBudgetOfItsAddressNorTheOneOfItsAccount() throws Exception {
    GlobalCeilingWindow.startWithRoomIn(jdbcTemplate, environment, "search");
    for (int request = 0; request < 2; request++) {
      assertThat(fetch("Bearer " + tokenValue)).isEqualTo(404);
    }

    // three searches of the account from the same address: its whole address budget is left, and
    // its account budget is apart from the token's - shared, the third would be the fifth
    String session = signIn("192.0.2.11");
    for (int request = 0; request < 3; request++) {
      assertThat(fetch(session)).as("search %d of the account", request + 1).isNotEqualTo(429);
    }
    // the five searches together spent the ceiling
    assertThat(fetch("Bearer " + tokenValue)).isEqualTo(429);
  }

  @Test
  void allTokensOfOnePersonShareOneBudget() throws Exception {
    String secondToken = "Bearer " + issueToken("Zweites Skript");
    for (int request = 0; request < 2; request++) {
      assertThat(fetch("Bearer " + tokenValue)).isEqualTo(404);
      assertThat(fetch(secondToken)).isEqualTo(404);
    }

    // the fifth request of the person: refused by her budget, not by the ceiling of five
    assertThat(fetch(secondToken)).isEqualTo(429);
    assertThat(fetch(signIn("192.0.2.14"), "192.0.2.15")).isNotEqualTo(429);
  }

  @Test
  void requestsTheTokenQuotaRefusesGiveBackTheirShareOfTheCeiling() throws Exception {
    openChannel(1);
    assertThat(fetch("Bearer " + tokenValue)).isEqualTo(404);
    for (int request = 0; request < 3; request++) {
      assertThat(fetch("Bearer " + tokenValue)).isEqualTo(429);
    }

    // one search of the ceiling of five is spent; four are left for the account
    String session = signIn("192.0.2.16");
    for (int request = 0; request < 4; request++) {
      String client = request < 2 ? "192.0.2.17" : "192.0.2.18";
      assertThat(fetch(session, client))
          .as("search %d of the account", request + 1)
          .isNotEqualTo(429);
    }
  }

  private int fetch(String authorization) throws Exception {
    return fetch(authorization, SHARED_ADDRESS);
  }

  private int fetch(String authorization, String client) throws Exception {
    return mockMvc
        .perform(
            get("/api/v1/search/hits/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .with(
                    request -> {
                      request.setRemoteAddr(client);
                      return request;
                    }))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private String signIn(String client) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/auth/local/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\""
                            + anna.email()
                            + "\",\"password\":\""
                            + LocalAccountFixtures.PASSWORD
                            + "\"}")
                    .with(
                        request -> {
                          request.setRemoteAddr(client);
                          return request;
                        }))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return "Bearer " + JsonPath.read(body, "$.accessToken");
  }

  private void openChannel(int tokenRequestsPerHour) {
    ExternalAccessSettings.Values values = settings.current().values();
    settings.update(
        callerOf(administrator),
        new ExternalAccessSettingsService.Update(
            true,
            values.tokenMaxLifetimeDays(),
            tokenRequestsPerHour,
            List.of(CHANNEL_NETWORK),
            values.massRetrievalAlertThreshold(),
            values.serverInstructions()));
  }

  private static CurrentUser callerOf(User user) {
    return CurrentUser.of(
        user.getId(), user.getOrganizationId(), user.getSystemRole(), "Grenzen-Test");
  }

  /** The library is empty; its history rows hold its owner (RESTRICT). */
  private void removeLibrary() {
    jdbcTemplate.update("DELETE FROM asset_visibility_history WHERE asset_id = ?", libraryId);
    jdbcTemplate.update("DELETE FROM asset_grant_history WHERE asset_id = ?", libraryId);
    jdbcTemplate.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", libraryId);
    jdbcTemplate.update("DELETE FROM assets WHERE id = ?", libraryId);
  }

  /** The row of the search ceiling, which this class and the chain test drive to its end. */
  private void resetSearchCeiling() {
    jdbcTemplate.update(
        "DELETE FROM installation_counters WHERE counter_key = ?", "rate-limit:search");
  }
}
