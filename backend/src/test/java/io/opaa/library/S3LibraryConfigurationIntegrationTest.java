package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static io.opaa.library.LibraryUpdateBuilder.libraryUpdate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code S3} as a library's quellentyp (ADR-0027, #1375): endpoint normalised and stored in {@code
 * sourceUrl}, credentials parsed and never returned - not even in the raw HTTP body -, the typed
 * settings validated and stored as JSON, the scopes changeable, the type immutable, and the target
 * validation refusing a private endpoint, proxy or bucket host before anything is stored. No object
 * store is contacted: saving resolves and checks addresses only.
 */
// Own Spring context on purpose: saving an S3 library passes its endpoint through the target
// validation, and the only endpoint a test can name without depending on public DNS is localhost -
// which that validation rejects unless allowlisted. The validation itself stays on, so the private
// endpoint, proxy and bucket-host refusals below are the real ones. MockMvc for the raw-body check
// of the API responses.
@OpaaIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "opaa.indexing.target-validation.allowlist=localhost")
class S3LibraryConfigurationIntegrationTest {

  private static final String ENDPOINT = "http://localhost:9000";

  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private io.opaa.indexing.source.s3.S3SyncStateRepository syncStateRepository;
  @Autowired private MockMvc mockMvc;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private final List<UUID> userIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "S3-Test-Org " + UUID.randomUUID()))
            .getId();
  }

  @AfterEach
  void tearDown() {
    List<KnowledgeLibrary> own =
        libraryRepository.findAll().stream()
            .filter(l -> organizationId.equals(l.getOrganizationId()))
            .toList();
    libraryRepository.deleteAll(own);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "DELETE FROM asset_grant_history WHERE subject_user_id IN (SELECT id FROM users WHERE"
            + " organization_id = ?)",
        organizationId);
    for (UUID userId : userIds) {
      userRepository.deleteById(userId);
    }
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void createsAnS3LibraryWithNormalisedEndpointAndSettingsAndNeverReturnsTheKeys() {
    UUID owner = user();
    LibraryCreation request =
        s3("Protokolle", ENDPOINT + "/")
            .sourceCredentials("AKIAEXAMPLE:geheim/4711:session-token")
            .s3Settings(
                settings(
                    "eu-central-1",
                    true,
                    S3Scope.of("protokolle", "/2025"),
                    S3Scope.of("satzungen", "")))
            .build();

    LibraryDetail detail = libraryService.createLibrary(request, currentUser(owner));

    KnowledgeLibrary library = detail.library();
    assertThat(library.getSourceType()).isEqualTo(DocumentSourceType.S3);
    assertThat(library.getSourceUrl()).isEqualTo(ENDPOINT);
    assertThat(library.getS3Settings().scopes())
        .containsExactly(S3Scope.of("protokolle", "2025/"), S3Scope.of("satzungen", ""));
    assertThat(library.getS3Settings().region()).isEqualTo("eu-central-1");
    assertThat(library.getS3Settings().pathStyle()).isTrue();
    assertThat(detail.managementDetail().sourceCredentialsSet()).isTrue();
    assertThat(library.getConfluenceSpaces()).isEmpty();

    KnowledgeLibrary reloaded = libraryRepository.findById(library.getId()).orElseThrow();
    assertThat(reloaded.getS3Settings()).isEqualTo(library.getS3Settings());
    assertThat(reloaded.getSourceCredentials()).isEqualTo("AKIAEXAMPLE:geheim/4711:session-token");
    String storedSettings =
        jdbcTemplate.queryForObject(
            "SELECT source_settings::text FROM knowledge_libraries WHERE id = ?",
            String.class,
            library.getId());
    assertThat(storedSettings).contains("\"scopes\"").doesNotContain("geheim");
  }

  @Test
  void theRawHttpResponsesCarryTheSettingsButNeverTheKeys() throws Exception {
    String secret = "hochgeheimer-secret-key-4711";
    String body =
        """
        {
          "name": "Protokolle per HTTP",
          "sourceType": "S3",
          "sourceUrl": "%s",
          "sourceCredentials": "AKIAEXAMPLE:%s",
          "s3Settings": {
            "region": "eu-central-1",
            "pathStyle": true,
            "scopes": [{"bucket": "protokolle", "prefix": "2025"}],
            "includePatterns": ["**/*.pdf"]
          }
        }
        """
            .formatted(ENDPOINT, secret);

    String created =
        mockMvc
            .perform(
                post("/api/v1/libraries")
                    .header(DevAuthFilter.DEV_USER_HEADER, "dev-user")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String libraryId = created.replaceAll("(?s).*\"id\":\"([0-9a-f-]{36})\".*", "$1");
    try {
      String fetched =
          mockMvc
              .perform(
                  get("/api/v1/libraries/" + libraryId)
                      .header(DevAuthFilter.DEV_USER_HEADER, "dev-user"))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString(StandardCharsets.UTF_8);
      for (String raw : List.of(created, fetched)) {
        assertThat(raw)
            .contains("\"sourceType\":\"S3\"")
            .contains("\"sourceUrl\":\"" + ENDPOINT + "\"")
            .contains("\"s3Settings\":{")
            .contains("\"bucket\":\"protokolle\"")
            .contains("\"prefix\":\"2025/\"")
            .contains("\"sourceCredentialsSet\":true")
            .doesNotContain("\"sourceCredentials\":")
            .doesNotContain(secret)
            .doesNotContain("AKIAEXAMPLE");
      }
    } finally {
      mockMvc
          .perform(
              delete("/api/v1/libraries/" + libraryId)
                  .header(DevAuthFilter.DEV_USER_HEADER, "dev-user"))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void rejectsIncompleteOrContradictoryConfiguration() {
    CurrentUser caller = currentUser(user());

    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    s3("ohne Settings", ENDPOINT).s3Settings(null).build(), caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("s3Settings sind erforderlich");
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    s3("ohne Schlüssel", ENDPOINT).sourceCredentials(null).build(), caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceCredentials sind erforderlich");
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    s3("Doppelpunkt", ENDPOINT).sourceCredentials("AK:IA:geheim:x").build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Doppelpunkt");
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    s3("nur Access Key", ENDPOINT).sourceCredentials("AKIAEXAMPLE").build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("accessKey:secretKey");
    assertThatThrownBy(
            () -> libraryService.createLibrary(s3("ohne Endpoint", null).build(), caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceUrl");
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    s3("Endpoint mit Pfad", ENDPOINT + "/bucket").build(), caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("keinen Pfad");
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    s3("mit Pfad", ENDPOINT).sourcePath("/srv/docs").build(), caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourcePath");
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    libraryCreation("RSS mit Settings", DocumentSourceType.RSS_FEED)
                        .sourceUrl(URI.create("https://example.org/feed.xml"))
                        .s3Settings(settings(null, true, S3Scope.of("dokumente", "")))
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nur für sourceType S3");
  }

  @Test
  void aPrivateEndpointWithoutAllowlistEntryIsRefusedBeforeAnythingIsStored() {
    CurrentUser caller = currentUser(user());

    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    s3("MinIO intern", "http://10.0.0.5:9000").build(), caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("10.0.0.5")
        .hasMessageContaining("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    s3("Proxy intern", ENDPOINT).sourceProxy("10.0.0.9:3128").build(), caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("10.0.0.9");
    // virtual-host addressing contacts <bucket>.<host>: the allowlisted endpoint passes, the
    // bucket host "dokumente.localhost" is not allowlisted and is refused - as blocked where the
    // resolver maps *.localhost to loopback, as unreachable where it resolves nothing; either way
    // the message names the host and nothing is stored
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    s3("Virtual-Host", ENDPOINT)
                        .s3Settings(settings(null, false, S3Scope.of("dokumente", "")))
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("dokumente.localhost");
    assertThat(
            libraryRepository.findAll().stream()
                .filter(l -> organizationId.equals(l.getOrganizationId())))
        .isEmpty();
  }

  @Test
  void theEventTokenIsGeneratedOnceEncryptedAtRestRotatedAndRemovedWithAnAuditTrail() {
    UUID owner = user();
    CurrentUser caller = currentUser(owner);
    UUID libraryId =
        libraryService.createLibrary(s3("Protokolle", ENDPOINT).build(), caller).library().getId();
    assertThat(libraryService.getLibrary(libraryId, caller).managementDetail().s3EventsTokenSet())
        .isFalse();

    String first = libraryService.generateS3EventsToken(libraryId, caller);
    assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]+");
    assertThat(libraryRepository.findById(libraryId).orElseThrow().getWebhookSecret())
        .isEqualTo(first);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT source_webhook_secret FROM knowledge_libraries WHERE id = ?",
                String.class,
                libraryId))
        .as("encrypted at rest like the credentials")
        .startsWith("enc:v1:")
        .doesNotContain(first);
    assertThat(libraryService.getLibrary(libraryId, caller).managementDetail().s3EventsTokenSet())
        .isTrue();
    assertThat(
            libraryService
                .getLibrary(libraryId, caller)
                .managementDetail()
                .confluenceWebhookSecretSet())
        .as("the Confluence flag stays absent for an S3 library")
        .isNull();

    String second = libraryService.generateS3EventsToken(libraryId, caller);
    assertThat(second).isNotEqualTo(first);
    libraryService.removeS3EventsToken(libraryId, caller);
    assertThat(libraryRepository.findById(libraryId).orElseThrow().getWebhookSecret()).isNull();
    libraryService.removeS3EventsToken(libraryId, caller);

    List<String> audit =
        jdbcTemplate.queryForList(
            "SELECT after FROM audit_log WHERE object_id = ? AND event_type = ?"
                + " ORDER BY recorded_at",
            String.class,
            libraryId.toString(),
            "LIBRARY_SOURCE_UPDATED");
    assertThat(audit)
        .as("generate, rotate, remove - the idempotent second removal leaves no entry")
        .hasSize(3)
        .allSatisfy(
            payload -> assertThat(payload).contains("s3EventsToken").doesNotContain(second));
    assertThatThrownBy(() -> libraryService.generateConfluenceWebhookSecret(libraryId, caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("CONFLUENCE");
  }

  @Test
  void theScopesAreReplaceableTheTypeIsNotAndCredentialsSurviveASameOriginEdit() {
    UUID owner = user();
    CurrentUser caller = currentUser(owner);
    UUID libraryId =
        libraryService.createLibrary(s3("Protokolle", ENDPOINT).build(), caller).library().getId();
    io.opaa.indexing.source.s3.S3SyncState resumption =
        new io.opaa.indexing.source.s3.S3SyncState(libraryId);
    resumption.beginFullSync(UUID.randomUUID());
    resumption.markScopeCompleted("protokolle/2025/");
    syncStateRepository.save(resumption);

    // settings only: replaced as a whole, nothing else touched
    LibraryDetail widened =
        libraryService.updateLibrary(
            libraryId,
            libraryUpdate("Protokolle")
                .s3Settings(
                    settings(
                        "eu-west-1",
                        true,
                        S3Scope.of("protokolle", "2025/"),
                        S3Scope.of("protokolle", "2024/")))
                .build(),
            caller);
    assertThat(widened.library().getS3Settings().scopes())
        .containsExactly(S3Scope.of("protokolle", "2025/"), S3Scope.of("protokolle", "2024/"));
    assertThat(widened.library().getS3Settings().region()).isEqualTo("eu-west-1");
    assertThat(widened.library().getSourceCredentials()).isEqualTo("AKIAEXAMPLE:geheim");
    assertThat(syncStateRepository.findByLibraryId(libraryId))
        .as("a changed selection discards the resumption state (ADR-0027, Entscheidung 3)")
        .isEmpty();
    List<String> audit =
        jdbcTemplate.queryForList(
            "SELECT after FROM audit_log WHERE object_id = ? AND event_type = ?",
            String.class,
            libraryId.toString(),
            "LIBRARY_SOURCE_UPDATED");
    assertThat(audit)
        .singleElement()
        .satisfies(payload -> assertThat(payload).contains("s3Settings"));

    // a rename alone leaves the settings - and the resumption state - untouched
    syncStateRepository.save(new io.opaa.indexing.source.s3.S3SyncState(libraryId));
    LibraryDetail renamed =
        libraryService.updateLibrary(libraryId, libraryUpdate("Sitzungen").build(), caller);
    assertThat(renamed.library().getS3Settings()).isEqualTo(widened.library().getS3Settings());
    assertThat(syncStateRepository.findByLibraryId(libraryId)).isPresent();

    // an endpoint edit on the same origin keeps the stored key; a new origin drops it
    LibraryDetail sameOrigin =
        libraryService.updateLibrary(
            libraryId,
            libraryUpdate("Sitzungen").sourceUrl(URI.create(ENDPOINT + "/")).build(),
            caller);
    assertThat(sameOrigin.library().getSourceCredentials()).isEqualTo("AKIAEXAMPLE:geheim");
    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    libraryId,
                    libraryUpdate("Sitzungen")
                        .sourceUrl(URI.create("http://localhost:9001"))
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceCredentials sind erforderlich");

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    libraryId,
                    libraryUpdate("Sitzungen")
                        .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceType kann nach dem Anlegen");
  }

  @Test
  void settingsAreRefusedOnALibraryOfAnotherType() {
    CurrentUser caller = currentUser(user());
    UUID rss =
        libraryService
            .createLibrary(
                libraryCreation("Feed", DocumentSourceType.RSS_FEED)
                    .sourceUrl(URI.create("https://example.org/feed.xml"))
                    .visibility(LibraryVisibility.PRIVATE)
                    .build(),
                caller)
            .library()
            .getId();

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    rss,
                    libraryUpdate("Feed")
                        .s3Settings(settings(null, true, S3Scope.of("dokumente", "")))
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nur für sourceType S3");
  }

  private static S3SourceSettings settings(String region, boolean pathStyle, S3Scope... scopes) {
    return new S3SourceSettings(region, pathStyle, List.of(scopes), null, null);
  }

  private static LibraryCreationBuilder s3(String name, String endpoint) {
    return libraryCreation(name, DocumentSourceType.S3)
        .sourceUrl(endpoint == null ? null : URI.create(endpoint))
        .sourceCredentials("AKIAEXAMPLE:geheim")
        .s3Settings(settings("eu-central-1", true, S3Scope.of("protokolle", "2025/")))
        .visibility(LibraryVisibility.PRIVATE);
  }

  private UUID user() {
    User user = new User("s3-" + UUID.randomUUID(), "https://issuer.example", null, "S3 Tester");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    userIds.add(id);
    return id;
  }

  private CurrentUser currentUser(UUID userId) {
    return CurrentUser.of(userId, organizationId, SystemRole.USER, "S3 Tester");
  }
}
