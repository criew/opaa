package io.opaa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
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
 * Controller-level coverage of {@code POST /api/v1/libraries/source-test} (#514): wiring,
 * authentication, and that a caller need not own or hold any role on an (as yet nonexistent)
 * library - the same minimum bar {@code POST /api/v1/libraries} itself applies. {@link
 * io.opaa.library.SourceConnectionTestServiceTest} already covers the per-quellentyp behaviour in
 * depth; this class only pins the HTTP layer around it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class LibraryControllerSourceTestIntegrationTest {

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
  void rejectsAnUnknownDevUserWithA401JustLikeEveryOtherLibraryEndpoint() throws Exception {
    // DevAuthFilter's own contract (see its Javadoc): an unrecognised X-OPAA-Dev-User subject is
    // rejected with 401 rather than silently falling back to the default user - this endpoint
    // goes through the exact same filter as every other /api/v1/libraries endpoint, so it must
    // reject the same way.
    String body =
        """
        { "sourceType": "UPLOAD" }
        """;

    mockMvc
        .perform(
            post("/api/v1/libraries/source-test")
                .header(DevAuthFilter.DEV_USER_HEADER, "no-such-user")
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .content(body))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsUploadSourceTypeWithA400() throws Exception {
    String body =
        """
        { "sourceType": "UPLOAD" }
        """;

    mockMvc
        .perform(post("/api/v1/libraries/source-test").with(devUser()).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsANonHttpUrlWithA400() throws Exception {
    String body =
        """
        { "sourceType": "HTTP_DIRECTORY", "sourceUrl": "ftp://files.example.com" }
        """;

    mockMvc
        .perform(post("/api/v1/libraries/source-test").with(devUser()).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reportsAnUnreachableHttpDirectoryAs200WithReachableFalse() throws Exception {
    // Port 1 is a reserved, never-listening port - the connection itself fails, distinct from
    // the source configuration being invalid (400 above).
    String body =
        """
        { "sourceType": "HTTP_DIRECTORY", "sourceUrl": "http://127.0.0.1:1/" }
        """;

    mockMvc
        .perform(post("/api/v1/libraries/source-test").with(devUser()).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reachable").value(false))
        .andExpect(jsonPath("$.message").isNotEmpty());
  }

  @Test
  void filesystemOutsideTheAllowlistIsRejectedWithA400JustLikeLibraryCreation() throws Exception {
    // Mirrors LibraryControllerCredentialsIntegrationTest's dev-profile allowlist expectations
    // (application.yml: opaa.indexing.filesystem-allowlist=/data,/tmp for the "dev" profile) -
    // path enumeration outside it must be rejected exactly like POST /api/v1/libraries itself.
    String body =
        """
        { "sourceType": "FILESYSTEM", "sourcePath": "/etc/shadow" }
        """;

    mockMvc
        .perform(post("/api/v1/libraries/source-test").with(devUser()).content(body))
        .andExpect(status().isBadRequest());
  }
}
