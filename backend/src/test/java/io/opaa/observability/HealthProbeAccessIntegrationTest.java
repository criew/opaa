package io.opaa.observability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.test.OpaaLocalAuthMockMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The two probes answer without a session (#1710) - a load balancer and a container health check
 * have none. Runs in the {@code oidc} context, the only one where authentication is actually
 * enforced: under {@code local,dev} every request counts as signed in.
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

  @Test
  void aProbeWithoutASessionShowsNoComponents() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(jsonPath("$.components").doesNotExist());
  }
}
