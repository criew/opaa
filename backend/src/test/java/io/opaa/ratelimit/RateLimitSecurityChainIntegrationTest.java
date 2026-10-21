package io.opaa.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthRateLimitTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The rate limits in front of the production {@code oidc} chain and the push intake: which requests
 * keep their tokens is decided by whether the security chain (or, for the push intake, its handler)
 * authenticated them - never by the status code. Limits from {@link OpaaLocalAuthRateLimitTest}:
 * indexing one per client and library and five globally, query three globally, webhook three per
 * client and four globally, search three per client and five globally, all per minute. Every test
 * uses its own addresses; the limiters per client live as long as the shared context, the global
 * ceilings in {@code installation_counters}, whose rows of these rules every test resets.
 */
@OpaaLocalAuthRateLimitTest
class RateLimitSecurityChainIntegrationTest {

  private static final int GLOBAL_INDEXING_CEILING = 5;
  private static final int GLOBAL_QUERY_CEILING = 3;
  private static final int WEBHOOK_PER_CLIENT = 3;
  private static final int GLOBAL_WEBHOOK_CEILING = 4;
  private static final int SEARCH_PER_CLIENT = 3;
  private static final int GLOBAL_SEARCH_CEILING = 5;

  /** One byte above the push intake's body bound, which it refuses before any secret check. */
  private static final int OVERSIZED_PUSH_BYTES = 256 * 1024 + 1;

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private JdbcTemplate jdbcTemplate;

  private LocalAccountFixtures fixtures;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    resetGlobalCeilings();
  }

  @AfterEach
  void tearDown() {
    fixtures.cleanUp();
    resetGlobalCeilings();
  }

  /** No other class drives these rules with ceilings this small. */
  private void resetGlobalCeilings() {
    jdbcTemplate.update(
        "DELETE FROM installation_counters WHERE counter_key IN (?, ?, ?, ?)",
        InstallationRateLimit.KEY_PREFIX + "indexing",
        InstallationRateLimit.KEY_PREFIX + "query",
        InstallationRateLimit.KEY_PREFIX + "webhook",
        InstallationRateLimit.KEY_PREFIX + "search");
  }

  @Test
  void requestsTheSecurityChainRefusesAsUnauthenticatedSpendNoGlobalIndexingToken()
      throws Exception {
    for (int stranger = 1; stranger <= GLOBAL_INDEXING_CEILING + 3; stranger++) {
      mockMvc
          .perform(
              post("/api/v1/libraries/" + UUID.randomUUID() + "/indexing")
                  .with(from("198.51.100." + stranger)))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  void requestsTheFirewallRejectsBeforeTheChainSpendNoGlobalIndexingToken() throws Exception {
    // the rule matches the path without its ";" parameter, the firewall rejects the raw URI with
    // 400 before any authentication - such a request did not authenticate either
    for (int stranger = 1; stranger <= GLOBAL_INDEXING_CEILING + 3; stranger++) {
      mockMvc
          .perform(
              post("/api/v1/libraries/" + UUID.randomUUID() + ";x/indexing")
                  .with(from("198.51.100." + (20 + stranger))))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void aRequestRefusedForAnExpiredSessionLeavesTheClientBudgetForItsRetry() throws Exception {
    // the frontend renews its token after a 401 and repeats the request at once
    UUID library = UUID.randomUUID();
    String client = "198.51.100.40";
    mockMvc
        .perform(post("/api/v1/libraries/" + library + "/indexing").with(from(client)))
        .andExpect(status().isUnauthorized());

    int retry =
        mockMvc
            .perform(
                post("/api/v1/libraries/" + library + "/indexing")
                    .header(HttpHeaders.AUTHORIZATION, signIn("198.51.100.41"))
                    .with(from(client)))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(retry).isNotEqualTo(429);
  }

  @Test
  void anAuthenticatedRequestKeepsItsGlobalToken() throws Exception {
    String bearer = signIn("198.51.100.50");
    for (int person = 1; person <= GLOBAL_QUERY_CEILING; person++) {
      assertThat(query(bearer, "198.51.100." + (50 + person))).isNotEqualTo(429);
    }
    assertThat(query(bearer, "198.51.100.59")).isEqualTo(429);
  }

  @Test
  void pushesRefusedBeforeTheSecretCheckSpendNoGlobalWebhookToken() throws Exception {
    for (int stranger = 1; stranger <= GLOBAL_WEBHOOK_CEILING + 2; stranger++) {
      mockMvc
          .perform(oversizedPush(UUID.randomUUID(), "198.51.100." + (60 + stranger)))
          .andExpect(status().isPayloadTooLarge());
    }
  }

  @Test
  void pushesRefusedBeforeTheSecretCheckDrawOnTheBudgetOfTheirAddress() throws Exception {
    String client = "198.51.100.70";
    for (int attempt = 0; attempt < WEBHOOK_PER_CLIENT; attempt++) {
      mockMvc
          .perform(oversizedPush(UUID.randomUUID(), client))
          .andExpect(status().isPayloadTooLarge());
    }
    mockMvc
        .perform(oversizedPush(UUID.randomUUID(), client))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void pushesWithoutAValidSecretDrawOnTheBudgetOfTheirAddress() throws Exception {
    String client = "198.51.100.80";
    for (int attempt = 0; attempt < WEBHOOK_PER_CLIENT; attempt++) {
      mockMvc.perform(push(UUID.randomUUID(), client)).andExpect(status().isUnauthorized());
    }
    mockMvc.perform(push(UUID.randomUUID(), client)).andExpect(status().isTooManyRequests());
  }

  @Test
  void aSignedInPersonIsRefusedPastHerSearchBudgetWhileAnotherClientSearchesOn() throws Exception {
    String bearer = signIn("198.51.100.100");
    String client = "198.51.100.101";
    for (int request = 0; request < SEARCH_PER_CLIENT; request++) {
      MockHttpServletResponse admitted =
          searchOrFetch(bearer, client, request % 2 == 0).andReturn().getResponse();
      assertThat(admitted.getStatus()).isNotEqualTo(429);
    }

    searchOrFetch(bearer, client, true)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
        .andExpect(jsonPath("$.error").value(RateLimitFilter.RATE_LIMIT_MESSAGE));
    searchOrFetch(bearer, client, false).andExpect(status().isTooManyRequests());

    int elsewhere =
        searchOrFetch(bearer, "198.51.100.102", true).andReturn().getResponse().getStatus();
    assertThat(elsewhere).isNotEqualTo(429);
  }

  @Test
  void searchesTheSecurityChainRefusesAsUnauthenticatedSpendNoSearchBudget() throws Exception {
    String client = "198.51.100.110";
    for (int attempt = 0; attempt < GLOBAL_SEARCH_CEILING + 2; attempt++) {
      searchOrFetch(null, client, attempt % 2 == 0).andExpect(status().isUnauthorized());
    }

    int signedIn =
        searchOrFetch(signIn("198.51.100.111"), client, true).andReturn().getResponse().getStatus();
    assertThat(signedIn).isNotEqualTo(429);
  }

  @Test
  void theSearchCeilingCountsSignedInSearchesOfAllClients() throws Exception {
    String bearer = signIn("198.51.100.120");
    for (int client = 1; client <= GLOBAL_SEARCH_CEILING; client++) {
      int status =
          searchOrFetch(bearer, "198.51.100." + (120 + client), client % 2 == 0)
              .andReturn()
              .getResponse()
              .getStatus();
      assertThat(status).isNotEqualTo(429);
    }
    searchOrFetch(bearer, "198.51.100.130", true).andExpect(status().isTooManyRequests());
  }

  /**
   * A search without a question (refused as invalid) or the fetch of an unknown hit: both reach
   * their handler without asking a model, and both count against the one search budget.
   */
  private ResultActions searchOrFetch(String bearer, String client, boolean search)
      throws Exception {
    MockHttpServletRequestBuilder request =
        search
            ? post("/api/v1/search").contentType(MediaType.APPLICATION_JSON).content("{}")
            : get("/api/v1/search/hits/" + UUID.randomUUID());
    if (bearer != null) {
      request.header(HttpHeaders.AUTHORIZATION, bearer);
    }
    return mockMvc.perform(request.with(from(client)));
  }

  private int query(String bearer, String client) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/query")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(from(client)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private String signIn(String client) throws Exception {
    LocalAccount user = fixtures.activeUser("rate-" + UUID.randomUUID() + "@stadt.example");
    String body =
        mockMvc
            .perform(
                post("/api/v1/auth/local/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\""
                            + user.email()
                            + "\",\"password\":\""
                            + LocalAccountFixtures.PASSWORD
                            + "\"}")
                    .with(from(client)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return "Bearer " + JsonPath.read(body, "$.accessToken");
  }

  private static MockHttpServletRequestBuilder push(UUID libraryId, String client) {
    return post("/api/v1/libraries/" + libraryId + "/push")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}")
        .with(from(client));
  }

  private static MockHttpServletRequestBuilder oversizedPush(UUID libraryId, String client) {
    return post("/api/v1/libraries/" + libraryId + "/push")
        .contentType(MediaType.APPLICATION_JSON)
        .content(new byte[OVERSIZED_PUSH_BYTES])
        .with(from(client));
  }

  private static RequestPostProcessor from(String remoteAddr) {
    return request -> {
      request.setRemoteAddr(remoteAddr);
      return request;
    };
  }
}
