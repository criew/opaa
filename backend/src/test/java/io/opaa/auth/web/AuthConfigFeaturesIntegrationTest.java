package io.opaa.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.test.OpaaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole application without any {@code opaa.features.*} configuration reports every feature
 * flag as switched on in {@code GET /api/v1/auth/config}.
 */
@OpaaIntegrationTest
class AuthConfigFeaturesIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void withoutConfigurationEveryFeatureFlagIsOn() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.features.promptLibraries").value(true))
        .andExpect(jsonPath("$.features.metadata").value(true));
  }
}
