package io.opaa.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.test.OpaaLocalAuthRateLimitTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The rate limits in front of the production {@code oidc} chain: the filter runs before the
 * security chain and reads the answer the chain gave. Shipped limits of the indexing trigger - one
 * per client and library, five across all clients per minute.
 */
@OpaaLocalAuthRateLimitTest
class RateLimitSecurityChainIntegrationTest {

  private static final int SHIPPED_GLOBAL_INDEXING_CEILING = 5;

  @Autowired private MockMvc mockMvc;

  @Test
  void requestsTheSecurityChainRefusesAsUnauthenticatedSpendNoGlobalIndexingToken()
      throws Exception {
    for (int stranger = 1; stranger <= SHIPPED_GLOBAL_INDEXING_CEILING + 3; stranger++) {
      String remoteAddr = "198.51.100." + stranger;
      mockMvc
          .perform(
              post("/api/v1/libraries/" + UUID.randomUUID() + "/indexing")
                  .with(
                      request -> {
                        request.setRemoteAddr(remoteAddr);
                        return request;
                      }))
          .andExpect(status().isUnauthorized());
    }
  }
}
