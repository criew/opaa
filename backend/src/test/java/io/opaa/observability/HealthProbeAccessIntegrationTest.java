package io.opaa.observability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.test.OpaaLocalAuthMockMvcTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Who may ask which health path (#1710). The two probes answer without a session - a load balancer
 * and a container health check have none - while every other group needs a token: the endpoint
 * computes its contributors before deciding what to show, so an anonymous call to {@code
 * embedding-model} or {@code vector-store} would trigger a real embedding call and a real
 * similarity search, outside the rate limit. Runs in the {@code oidc} context, the only one where
 * authentication is enforced: under {@code local,dev} every request counts as signed in.
 */
@OpaaLocalAuthMockMvcTest
class HealthProbeAccessIntegrationTest {

  @Autowired private MockMvc mockMvc;

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

  @ParameterizedTest
  @ValueSource(strings = {"chat-model", "embedding-model", "vector-store", "mail", "upload-store"})
  void everyOtherGroupNeedsASession(String group) throws Exception {
    mockMvc.perform(get("/actuator/health/" + group)).andExpect(status().isUnauthorized());
  }
}
