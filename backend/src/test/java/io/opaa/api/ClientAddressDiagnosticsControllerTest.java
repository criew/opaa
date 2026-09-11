package io.opaa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaMockMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/admin/diagnostics/client-address} (ADR-0033, Entscheidung 9): the one
 * request-bound diagnostic - the client address as the rate limiter and the administrator network
 * restriction resolve it for exactly this request, next to what the connection and the header say,
 * so an operator sees without a shell whether the trusted-proxy list is right. System
 * administrators only. This context trusts no proxy, so a header is shown but not honoured.
 */
@OpaaMockMvcTest
class ClientAddressDiagnosticsControllerTest {

  private static final String PATH = "/api/v1/admin/diagnostics/client-address";

  @Autowired private MockMvc mockMvc;

  @Test
  void showsTheResolvedAddressNextToConnectionAndHeader() throws Exception {
    mockMvc
        .perform(
            get(PATH)
                .with(
                    request -> {
                      request.setRemoteAddr("203.0.113.7");
                      return request;
                    })
                .header("X-Forwarded-For", "198.51.100.1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clientAddress").value("203.0.113.7"))
        .andExpect(jsonPath("$.remoteAddress").value("203.0.113.7"))
        .andExpect(jsonPath("$.forwardedFor").value("198.51.100.1"))
        .andExpect(jsonPath("$.forwardedForTrusted").value(false))
        .andExpect(jsonPath("$.trustedProxiesConfigured").value(false));
  }

  @Test
  void aRequestWithoutTheHeaderShowsNoHeader() throws Exception {
    mockMvc
        .perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clientAddress").value("127.0.0.1"))
        .andExpect(jsonPath("$.forwardedFor").doesNotExist())
        .andExpect(jsonPath("$.forwardedForTrusted").value(false));
  }

  @Test
  void aRegularUserIsRefused() throws Exception {
    mockMvc
        .perform(get(PATH).header(DevAuthFilter.DEV_USER_HEADER, "dev-user"))
        .andExpect(status().isForbidden());
  }
}
