package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PR #489 review, Befund 6a: {@code sourceCredentials} must appear in no API response - not the
 * success path (already asserted at the object level by {@code
 * KnowledgeLibraryServiceIntegrationTest#createLibraryAcceptsAnHttpDirectorySourceTypeWithAUrlAndNeverReturnsCredentials}
 * via {@code LibraryResponse#toString}) and, just as important, not the failure path either - a
 * validation error or the database's own {@code chk_knowledge_libraries_source_configuration}
 * rejecting the row could otherwise surface the submitted credentials back to the caller via {@link
 * org.springframework.web.server.ResponseStatusException#getReason()} or a Postgres error message
 * forwarded verbatim (ADR-0018, Entscheidung 4). Asserts against the raw JSON response body via
 * MockMvc, not a Java object's toString(), because only the raw body is what an actual HTTP client
 * - and therefore any log or proxy that captures it - ever sees.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class LibraryControllerCredentialsIntegrationTest {

  private static final String SECRET = "admin:super-secret-password";

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private MockMvc mockMvc;

  private RequestPostProcessor devUser() {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-user");
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }

  @Test
  void creatingAnHttpDirectoryLibrarySucceedsAndTheRawResponseBodyNeverContainsTheCredentials()
      throws Exception {
    String body =
        """
        {
          "name": "Rechtsquellen mit Zugangsdaten",
          "sourceType": "HTTP_DIRECTORY",
          "sourceUrl": "https://files.example.com/documents/",
          "sourceCredentials": "%s"
        }
        """
            .formatted(SECRET);

    var result =
        mockMvc
            .perform(post("/api/v1/libraries").with(devUser()).content(body))
            .andExpect(status().isCreated())
            .andReturn();

    String rawResponseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(rawResponseBody).doesNotContain(SECRET);
    assertThat(rawResponseBody).doesNotContain("sourceCredentials");
  }

  @Test
  void aValidationErrorNeverEchoesTheSubmittedCredentialsInTheRawResponseBody() throws Exception {
    // FILESYSTEM rejects sourceCredentials outright (validateConfigurationForType) - the request
    // body that triggers the 400 still carries the plaintext credential, so this pins that
    // GlobalExceptionHandler's error response never reflects the request back.
    String body =
        """
        {
          "name": "Ungueltige Kombination",
          "sourceType": "FILESYSTEM",
          "sourcePath": "/data/documents",
          "sourceCredentials": "%s"
        }
        """
            .formatted(SECRET);

    var result =
        mockMvc
            .perform(post("/api/v1/libraries").with(devUser()).content(body))
            .andExpect(status().isBadRequest())
            .andReturn();

    String rawResponseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(rawResponseBody).doesNotContain(SECRET);
  }

  @Test
  void aSecondKindOfValidationErrorAlsoNeverEchoesTheSubmittedCredentials() throws Exception {
    // A second, independent 400 path (UPLOAD rejects any configuration at all, including a URL) -
    // guards against a fix that only special-cased the FILESYSTEM branch above. The database's own
    // chk_knowledge_libraries_source_configuration (whose error text would include every column
    // value, credentials included, per ADR-0018 Entscheidung 4) is exercised directly, without any
    // HTTP layer in between, by Migration027LibrarySourceTypeAndConfigurationTest.
    String body =
        """
        {
          "name": "Unzulaessige Kombination",
          "sourceType": "UPLOAD",
          "sourceUrl": "https://files.example.com/documents/",
          "sourceCredentials": "%s"
        }
        """
            .formatted(SECRET);

    var result =
        mockMvc
            .perform(post("/api/v1/libraries").with(devUser()).content(body))
            .andExpect(status().isBadRequest())
            .andReturn();

    String rawResponseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(rawResponseBody).doesNotContain(SECRET);
  }
}
