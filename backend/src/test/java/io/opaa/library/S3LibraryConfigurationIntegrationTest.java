package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static io.opaa.library.LibraryUpdateBuilder.libraryUpdate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code S3} as a library's quellentyp (ADR-0027, #1375): endpoint normalised and stored in {@code
 * sourceUrl}, credentials parsed and never returned, the typed settings validated and stored as
 * JSON, the scopes changeable, the type immutable - and the target validation of the shared context
 * refusing a private endpoint before anything is stored. No object store is contacted: saving
 * resolves and checks addresses only.
 */
@OpaaIntegrationTest
class S3LibraryConfigurationIntegrationTest {

  private static final String ENDPOINT = "https://s3.eu-central-1.amazonaws.com";

  @Autowired private KnowledgeLibraryService libraryService;
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
    assertThat(reloaded.getSourceSettingsJsonForTest()).doesNotContain("geheim");
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
    // virtual-host addressing contacts <bucket>.<host>: a bucket host that does not resolve is
    // reported as unreachable, not as an allowlist problem
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    s3("Virtual-Host", "https://s3.gibtsnicht.invalid")
                        .s3Settings(settings(null, false, S3Scope.of("dokumente", "")))
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nicht erreichbar");
    assertThat(
            libraryRepository.findAll().stream()
                .filter(l -> organizationId.equals(l.getOrganizationId())))
        .isEmpty();
  }

  @Test
  void theScopesAreReplaceableTheTypeIsNotAndCredentialsSurviveASameOriginEdit() {
    UUID owner = user();
    CurrentUser caller = currentUser(owner);
    UUID libraryId =
        libraryService.createLibrary(s3("Protokolle", ENDPOINT).build(), caller).library().getId();

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
    List<String> audit =
        jdbcTemplate.queryForList(
            "SELECT after FROM audit_log WHERE object_id = ? AND event_type = ?",
            String.class,
            libraryId.toString(),
            "LIBRARY_SOURCE_UPDATED");
    assertThat(audit)
        .singleElement()
        .satisfies(payload -> assertThat(payload).contains("s3Settings"));

    // a rename alone leaves the settings untouched
    LibraryDetail renamed =
        libraryService.updateLibrary(libraryId, libraryUpdate("Sitzungen").build(), caller);
    assertThat(renamed.library().getS3Settings()).isEqualTo(widened.library().getS3Settings());

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
                        .sourceUrl(URI.create("https://s3.us-east-1.amazonaws.com"))
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
