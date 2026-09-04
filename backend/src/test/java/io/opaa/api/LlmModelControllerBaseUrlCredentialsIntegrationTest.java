package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaMockMvcTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The HTTP surface of the chat role's base address rule (#1147): a base URL carrying userinfo is
 * rejected with 400, and neither the error body nor any later listing reproduces the credentials.
 * Asserted against the raw response body, because that is what an HTTP client, a proxy and every
 * log capturing it actually see - the same reasoning {@link
 * LibraryControllerCredentialsIntegrationTest} documents for library source credentials.
 */
@OpaaMockMvcTest
class LlmModelControllerBaseUrlCredentialsIntegrationTest {

  private static final String SECRET_IN_BASE_URL = "benutzer:geheim";
  private static final String CREDENTIALS_BASE_URL =
      "https://" + SECRET_IN_BASE_URL + "@modellserver.example.internal/v1";

  @Autowired private MockMvc mockMvc;

  @Test
  void creatingAModelWithCredentialsInTheBaseUrlIsRejectedWithoutEchoingThem() throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/admin/models")
                    .with(devAdmin())
                    .content(
                        """
                        {
                          "displayName": "Modell mit Anmeldedaten",
                          "baseUrl": "%s",
                          "modelIdentifier": "phi3:mini",
                          "temperature": 0.7,
                          "maxTokens": 2000
                        }
                        """
                            .formatted(CREDENTIALS_BASE_URL)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(body).doesNotContain(SECRET_IN_BASE_URL).doesNotContain("geheim");

    String listing =
        mockMvc
            .perform(get("/api/v1/admin/models").with(devAdmin()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(listing).doesNotContain("geheim");
  }

  @Test
  void testingAConnectionWithCredentialsInTheBaseUrlIsRejectedWithoutEchoingThem() throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/admin/models/test")
                    .with(devAdmin())
                    .content(
                        """
                        {"baseUrl": "%s", "modelIdentifier": "phi3:mini"}
                        """
                            .formatted(CREDENTIALS_BASE_URL)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(body).doesNotContain("geheim");
  }

  private RequestPostProcessor devAdmin() {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-admin");
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }
}
