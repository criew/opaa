package io.opaa.observability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.test.OpaaLocalAuthMockMvcTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The metric endpoints need a session where no management port of their own is configured - the API
 * port of a backend reached directly. Runs in the {@code oidc} context, the only one where
 * authentication is enforced; the open management port is covered by {@link
 * ManagementPortMetricsIntegrationTest}. The rest of the actuator besides health and info needs a
 * session as well, whatever an operator exposes on top.
 */
@OpaaLocalAuthMockMvcTest
class MetricsEndpointAccessIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @ParameterizedTest
  @ValueSource(
      strings = {"/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/jvm.memory.used"})
  void aMetricEndpointNeedsASessionOnTheApiPort(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/actuator", "/actuator/env", "/actuator/heapdump", "/actuator/beans"})
  void theRestOfTheActuatorNeedsASession(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
  }
}
