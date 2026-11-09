package io.opaa.observability;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Who may ask which health path. The two probes answer without a session - a load balancer and a
 * container health check have none - while every other group needs a token: the endpoint computes
 * its contributors before deciding what to show, so an anonymous call to {@code embedding-model} or
 * {@code vector-store} would trigger a real embedding call and a real similarity search, outside
 * the rate limit. Runs in the {@code oidc} context, the only one where authentication is enforced:
 * under {@code local,dev} every request counts as signed in. The details of a group - provider,
 * endpoint, error - are for the system administration only; another signed-in account sees the
 * status alone.
 */
@OpaaLocalAuthMockMvcTest
class HealthProbeAccessIntegrationTest {

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
  void bothProbesAnswerWithoutASession() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void anAnonymousProbeShowsNoComponents() throws Exception {
    // What the handbook promises an operator, and the reason a deeper path answers 404 rather
    // than computing a contributor: without a session the probe carries its status and nothing
    // else.
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(jsonPath("$.components").doesNotExist());
  }

  @ParameterizedTest
  @ValueSource(strings = {"chat-model", "embedding-model", "vector-store", "mail", "upload-store"})
  void everyOtherGroupNeedsASession(String group) throws Exception {
    mockMvc.perform(get("/actuator/health/" + group)).andExpect(status().isUnauthorized());
  }

  @ParameterizedTest
  @ValueSource(strings = {"embedding-model", "vector-store", "chat-model", "mail"})
  // regression guard for ADR-0071, decision 5: details only for the system administration
  void anAccountWithoutTheSystemAdministrationSeesTheStatusOnly(String group) throws Exception {
    String bearer =
        bearer(fixtures.activeUser("health-" + UUID.randomUUID() + "@stadt.example").email());

    mockMvc
        .perform(get("/actuator/health/" + group).header(HttpHeaders.AUTHORIZATION, bearer))
        .andExpect(status().is(anyOf(is(200), is(503))))
        .andExpect(jsonPath("$.status", is(oneOf("UP", "DOWN", "UNKNOWN", "OUT_OF_SERVICE"))))
        .andExpect(jsonPath("$.components").doesNotExist())
        .andExpect(jsonPath("$.details").doesNotExist());
  }

  @Test
  void theSystemAdministrationSeesTheDetails() throws Exception {
    String bearer =
        bearer(fixtures.activeAdmin("health-" + UUID.randomUUID() + "@stadt.example").email());

    mockMvc
        .perform(get("/actuator/health/embedding-model").header(HttpHeaders.AUTHORIZATION, bearer))
        .andExpect(jsonPath("$.components.embeddings.status").exists())
        .andExpect(jsonPath("$.components.embeddings.details").exists());
  }

  private String bearer(String email) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/auth/local/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\""
                            + email
                            + "\",\"password\":\""
                            + LocalAccountFixtures.PASSWORD
                            + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return "Bearer " + JsonPath.read(body, "$.accessToken");
  }
}
