package io.opaa.llm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The upgrade of the live dictation under the production {@code oidc} chain: the access token of a
 * sign-in, handed over as subprotocol the way a browser must, authenticates the upgrade; no token
 * and an external-access token are refused before any session starts. MockMvc has no WebSocket
 * container, so an upgrade that got past authentication and caller check ends at the container.
 */
@OpaaLocalAuthMockMvcTest
class TranscriptionStreamBearerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;

  private LocalAccountFixtures fixtures;
  private LocalAccount user;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    user = fixtures.activeUser("live-" + UUID.randomUUID() + "@stadt.example");
  }

  @AfterEach
  void tearDown() {
    fixtures.cleanUp();
  }

  @Test
  void theAccessTokenOfASignInAsSubprotocolAuthenticatesTheUpgrade() throws Exception {
    String token = signIn();

    assertThatThrownBy(() -> mockMvc.perform(upgrade("opaa-bearer." + token)))
        .rootCause()
        .hasMessageContaining("ServerContainer");
  }

  @Test
  void anUpgradeWithoutTokenIsRefused() throws Exception {
    mockMvc.perform(upgrade(null)).andExpect(status().isUnauthorized());
  }

  @Test
  void anExternalAccessTokenIsRefusedAtTheUpgrade() throws Exception {
    int status =
        mockMvc
            .perform(
                upgrade("opaa-bearer.opaa_pat_" + UUID.randomUUID().toString().replace("-", "")))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).isIn(401, 403);
  }

  private static MockHttpServletRequestBuilder upgrade(String credentials) {
    MockHttpServletRequestBuilder request =
        get(TranscriptionStreamConfiguration.PATH)
            .header("Upgrade", "websocket")
            .header("Connection", "Upgrade")
            .header("Sec-WebSocket-Version", "13")
            .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
            .header("Sec-WebSocket-Protocol", TranscriptionStreamCredentialsFilter.PROTOCOL);
    if (credentials != null) {
      request.header("Sec-WebSocket-Protocol", credentials);
    }
    return request;
  }

  private String signIn() throws Exception {
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
                            + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.accessToken");
  }
}
