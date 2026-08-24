package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.TestcontainersConfiguration;
import io.opaa.auth.DevAuthFilter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

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
 *
 * <p><b>Also carries {@code POST /api/v1/libraries/source-test}'s HTTP-layer coverage (#514, PR
 * #537 review).</b> That endpoint originally had its own {@code
 * LibraryControllerSourceTestIntegrationTest} class - byte-for-byte the same
 * {@code @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("dev") @Testcontainers} shape as
 * this class, including its own {@code @Container} Postgres instance. Two test classes with an
 * identical shape still do <em>not</em> share a cached Spring context when each declares its own
 * {@code @DynamicPropertySource} method (Spring's context-cache key resolves the dynamic-property
 * customizer per declaring method, not per equivalent body) - so that second class meant a second,
 * fully independent {@code ApplicationContext} plus a second Postgres container alive at once,
 * which was the direct cause of the CI backend-test job's {@code OutOfMemoryError} once this PR
 * added it (a coordinator-reported CI failure, main itself green throughout). Moving those tests
 * here instead - onto the container and context this class needs regardless - removes that second
 * context entirely rather than trying to make two different classes share one.
 *
 * <p><b>Issue #497, measure 5:</b> replaced the class's own
 * {@code @Container}/{@code @DynamicPropertySource} pair with the shared {@link
 * TestcontainersConfiguration} for exactly the reason just described - this class, {@code
 * BrandingControllerIntegrationTest} and {@code AuditControllerAuthorizationIntegrationTest} now
 * carry the identical
 * {@code @SpringBootTest}/{@code @AutoConfigureMockMvc}/{@code @Import(TestcontainersConfiguration.class)}/{@code @ActiveProfiles("dev")}
 * signature and share one cached context and one container.
 *
 * <p><b>Caveat that comes with sharing that context:</b> the {@code RateLimitService} instances
 * behind {@code RateLimitFilter} are singleton beans, so their in-memory request counters are
 * <em>not</em> reset between test classes (or even between test methods) in this group - only a
 * fresh {@code ApplicationContext} would reset them, and this group deliberately avoids building
 * one per class. A future test added to this class, {@code BrandingControllerIntegrationTest} or
 * {@code AuditControllerAuthorizationIntegrationTest} that calls a rate-limited endpoint enough
 * times across the whole group's combined test run can observe an unexpected 429 that a standalone
 * run of that one class would not have shown.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class LibraryControllerCredentialsIntegrationTest {

  private static final String SECRET = "admin:super-secret-password";

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
    // The exact JSON key, not a bare substring match: PR #542 added the sibling field
    // sourceCredentialsSet (a non-secret yes/no, never the credential itself, issue #516/#542
    // review nit 3), which legitimately contains "sourceCredentials" as a prefix.
    assertThat(rawResponseBody).doesNotContain("\"sourceCredentials\":");
    assertThat(rawResponseBody).contains("\"sourceCredentialsSet\":true");
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

  // --- POST /api/v1/libraries/source-test (#514) -------------------------------------------
  //
  // Wiring, authentication, and that a caller need not own or hold any role on an (as yet
  // nonexistent) library - the same minimum bar POST /api/v1/libraries itself applies. {@link
  // io.opaa.library.SourceConnectionTestServiceTest} already covers the per-quellentyp behaviour
  // in depth; these only pin the HTTP layer around it. See this class's own Javadoc for why they
  // live here rather than in a dedicated test class.

  @Test
  void sourceTestRejectsAnUnknownDevUserWithA401JustLikeEveryOtherLibraryEndpoint()
      throws Exception {
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
  void sourceTestRejectsUploadSourceTypeWithA400() throws Exception {
    String body =
        """
        { "sourceType": "UPLOAD" }
        """;

    mockMvc
        .perform(post("/api/v1/libraries/source-test").with(devUser()).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void sourceTestRejectsANonHttpUrlWithA400() throws Exception {
    String body =
        """
        { "sourceType": "HTTP_DIRECTORY", "sourceUrl": "ftp://files.example.com" }
        """;

    mockMvc
        .perform(post("/api/v1/libraries/source-test").with(devUser()).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void sourceTestReportsAnUnreachableHttpDirectoryAs200WithReachableFalse() throws Exception {
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
  void sourceTestFilesystemOutsideTheAllowlistIsRejectedWithA400JustLikeLibraryCreation()
      throws Exception {
    // Mirrors this class's own dev-profile allowlist expectations (application.yml:
    // opaa.indexing.filesystem-allowlist=/data,/tmp for the "dev" profile) - path enumeration
    // outside it must be rejected exactly like POST /api/v1/libraries itself.
    String body =
        """
        { "sourceType": "FILESYSTEM", "sourcePath": "/etc/shadow" }
        """;

    mockMvc
        .perform(post("/api/v1/libraries/source-test").with(devUser()).content(body))
        .andExpect(status().isBadRequest());
  }

  // --- Mapper access to entity state outside the read-only service transaction --------------
  //
  // spring.jpa.open-in-view=false (application.yml): every mapper in io.opaa.api runs strictly
  // after the @Transactional(readOnly = true) service method that loaded the entities it maps has
  // already returned, exactly the boundary PR #870's review found a LazyInitializationException
  // across (SpaceResponseMapper reading a not-fetch-joined getMemberships() there). These three
  // list endpoints - one per mapper this PR/series adds - go through the real service and mapper
  // against a real, Testcontainers-backed database, not a mock: a mapper that touched a collection
  // Hibernate had not already loaded would surface here as a 500, not as a passing test with a
  // transient/mocked entity that hides the problem. KnowledgeLibrary, AssetGrant and Document carry
  // no JPA-managed collection relationships at all (unlike Space#memberships), so none of these
  // mappers has anything left to fetch-join in the first place - these tests pin that down for the
  // future, not just document it in prose.

  @Test
  void listLibrariesSucceedsThroughTheRealMapperOutsideTheServiceTransaction() throws Exception {
    String body =
        """
        { "name": "Listen-Test-Bibliothek", "sourceType": "UPLOAD" }
        """;
    mockMvc
        .perform(post("/api/v1/libraries").with(devUser()).content(body))
        .andExpect(status().isCreated());

    mockMvc
        .perform(get("/api/v1/libraries").with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.name == 'Listen-Test-Bibliothek')]").isNotEmpty());
  }

  @Test
  void listAssetGrantsSucceedsThroughTheRealMapperOutsideTheServiceTransaction() throws Exception {
    String body =
        """
        { "name": "Grant-Listen-Test-Bibliothek", "sourceType": "UPLOAD" }
        """;
    String createResponse =
        mockMvc
            .perform(post("/api/v1/libraries").with(devUser()).content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String libraryId = JsonPath.read(createResponse, "$.id");

    // createLibrary always grants the creator OWNER explicitly (KnowledgeLibraryService's class
    // Javadoc) - subjectDisplayName is resolved by AssetGrantService#toViews and mapped by
    // AssetGrantResponseMapper, neither of which touches a lazy relation.
    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/grants").with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].role").value("OWNER"))
        .andExpect(jsonPath("$[0].subjectDisplayName").isNotEmpty());
  }

  @Test
  void listDocumentsSucceedsThroughTheRealMapperOutsideTheServiceTransaction() throws Exception {
    String body =
        """
        { "name": "Dokumenten-Listen-Test-Bibliothek", "sourceType": "UPLOAD" }
        """;
    String createResponse =
        mockMvc
            .perform(post("/api/v1/libraries").with(devUser()).content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String libraryId = JsonPath.read(createResponse, "$.id");

    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/documents").with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.folders").isArray())
        .andExpect(jsonPath("$.breadcrumb").isArray());
  }
}
