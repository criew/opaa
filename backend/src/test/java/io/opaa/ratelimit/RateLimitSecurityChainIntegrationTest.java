package io.opaa.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The rate limits in front of the production {@code oidc} chain and the push intake: which requests
 * keep their tokens is decided by whether the security chain (or, for the push intake, its handler)
 * authenticated them - never by the status code. Limits from {@link OpaaLocalAuthRateLimitTest}:
 * indexing one per client and library and five globally, query three globally, webhook three per
 * client and four globally, all per minute. Every test uses its own addresses; the limiters live as
 * long as the shared context.
 */
@OpaaLocalAuthRateLimitTest
class RateLimitSecurityChainIntegrationTest {

  private static final int GLOBAL_INDEXING_CEILING = 5;
  private static final int GLOBAL_QUERY_CEILING = 3;
  private static final int WEBHOOK_PER_CLIENT = 3;
  private static final int GLOBAL_WEBHOOK_CEILING = 4;

  /** One byte above the push intake's body bound, which it refuses before any secret check. */
  private static final int OVERSIZED_PUSH_BYTES = 256 * 1024 + 1;

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;

  private LocalAccountFixtures fixtures;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
  }

  @AfterEach
  void tearDown() {
    fixtures.cleanUp();
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
