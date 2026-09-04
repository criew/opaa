package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static io.opaa.library.LibraryUpdateBuilder.libraryUpdate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.confluence.ConfluenceSyncState;
import io.opaa.indexing.source.confluence.ConfluenceSyncStateRepository;
import io.opaa.indexing.source.confluence.FakeConfluenceServer;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code CONFLUENCE} as a library's quellentyp (ADR-0023, #1133): configuration validated per
 * edition, edition immutable, space selection changeable and non-empty, credentials never returned,
 * and the multi-library model of the epic - several libraries against the same instance with the
 * same or different tokens and overlapping selections, created by different people.
 */
// Own Spring context on purpose: creating a CONFLUENCE library re-checks the edition against the
// instance (ADR-0023, Entscheidung 2), and the only instance a test can offer is the loopback test
// double - which the default target validation rejects. Every other @OpaaIntegrationTest class
// keeps
// the shared context; this property split is the one this class needs.
@OpaaIntegrationTest
@TestPropertySource(properties = "opaa.indexing.target-validation.enabled=false")
class ConfluenceLibraryConfigurationIntegrationTest {

  private FakeConfluenceServer cloud;
  private FakeConfluenceServer dataCenter;
  private FakeConfluenceServer secondDataCenter;

  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantService grantService;
  @Autowired private ConfluenceSyncStateRepository syncStateRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private final List<UUID> userIds = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    cloud = new FakeConfluenceServer(ConfluenceEdition.CLOUD);
    dataCenter = new FakeConfluenceServer(ConfluenceEdition.DATA_CENTER, "/confluence");
    secondDataCenter = new FakeConfluenceServer(ConfluenceEdition.DATA_CENTER);
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Confluence-Test-Org " + UUID.randomUUID()))
            .getId();
  }

  @AfterEach
  void tearDown() {
    cloud.close();
    dataCenter.close();
    secondDataCenter.close();
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
  void createsADataCenterLibraryWithNormalisedAddressAndSelectionAndNeverReturnsTheToken() {
    UUID owner = user();
    LibraryCreation request =
        libraryCreation("Wiki Bauamt", DocumentSourceType.CONFLUENCE)
            .sourceUrl(URI.create(dataCenter.baseUrl() + "/"))
            .sourceCredentials("pat-geheim")
            .confluenceEdition(ConfluenceEdition.DATA_CENTER)
            .confluenceSpaces(
                List.of(
                    new ConfluenceSpaceSelection("HR", "Personal"),
                    new ConfluenceSpaceSelection("BAU", "Bauamt")))
            .build();

    LibraryDetail detail = libraryService.createLibrary(request, currentUser(owner));

    KnowledgeLibrary library = detail.library();
    assertThat(library.getSourceType()).isEqualTo(DocumentSourceType.CONFLUENCE);
    assertThat(library.getSourceConfluenceEdition()).isEqualTo(ConfluenceEdition.DATA_CENTER);
    assertThat(library.getSourceUrl()).isEqualTo(dataCenter.baseUrl());
    assertThat(library.getConfluenceSpaces())
        .extracting(ConfluenceSpaceSelection::getSpaceKey)
        .containsExactly("BAU", "HR");
    assertThat(detail.managementDetail().sourceCredentialsSet()).isTrue();

    KnowledgeLibrary reloaded = libraryRepository.findById(library.getId()).orElseThrow();
    assertThat(reloaded.getConfluenceSpaces()).hasSize(2);
    assertThat(reloaded.getSourceCredentials()).isEqualTo("pat-geheim");
  }

  @Test
  void cloudNeedsEmailAndTokenAndLosesTheWikiSuffix() {
    UUID owner = user();
    LibraryCreation withoutEmail =
        confluence("Cloud ohne E-Mail", ConfluenceEdition.CLOUD, cloud.baseUrl() + "/wiki")
            .sourceCredentials("nur-token")
            .build();
    assertThatThrownBy(() -> libraryService.createLibrary(withoutEmail, currentUser(owner)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("E-Mail");

    LibraryDetail detail =
        libraryService.createLibrary(
            confluence("Cloud", ConfluenceEdition.CLOUD, cloud.baseUrl() + "/wiki")
                .sourceCredentials("dienst@behoerde.example:api-token")
                .build(),
            currentUser(owner));
    assertThat(detail.library().getSourceUrl()).isEqualTo(cloud.baseUrl());
    assertThat(detail.library().getSourceConfluenceEdition()).isEqualTo(ConfluenceEdition.CLOUD);
  }

  @Test
  void rejectsIncompleteOrContradictoryConfiguration() {
    UUID owner = user();
    CurrentUser caller = currentUser(owner);

    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    libraryCreation("ohne Edition", DocumentSourceType.CONFLUENCE)
                        .sourceUrl(URI.create(dataCenter.baseUrl()))
                        .sourceCredentials("pat")
                        .confluenceSpaces(List.of(new ConfluenceSpaceSelection("A", null)))
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("confluenceEdition ist erforderlich");
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    confluence("ohne Spaces", ConfluenceEdition.DATA_CENTER, dataCenter.baseUrl())
                        .confluenceSpaces(List.of())
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("mindestens ein Space");
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    confluence("ohne Spaces", ConfluenceEdition.DATA_CENTER, dataCenter.baseUrl())
                        .confluenceSpaces(null)
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("mindestens ein Space");
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    confluence("ohne Token", ConfluenceEdition.DATA_CENTER, dataCenter.baseUrl())
                        .sourceCredentials(null)
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceCredentials sind erforderlich");
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    confluence("doppelt", ConfluenceEdition.DATA_CENTER, dataCenter.baseUrl())
                        .confluenceSpaces(
                            List.of(
                                new ConfluenceSpaceSelection("A", null),
                                new ConfluenceSpaceSelection("A", "nochmal")))
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("mehrfach");
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    confluence("mit Pfad", ConfluenceEdition.DATA_CENTER, dataCenter.baseUrl())
                        .sourcePath("/srv/docs")
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourcePath");
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    libraryCreation("RSS mit Edition", DocumentSourceType.RSS_FEED)
                        .sourceUrl(URI.create("https://example.org/feed.xml"))
                        .confluenceEdition(ConfluenceEdition.CLOUD)
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nur für sourceType CONFLUENCE");
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    libraryCreation("RSS mit Spaces", DocumentSourceType.RSS_FEED)
                        .sourceUrl(URI.create("https://example.org/feed.xml"))
                        .confluenceSpaces(List.of(new ConfluenceSpaceSelection("A", null)))
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nur für sourceType CONFLUENCE");
  }

  @Test
  void creationRefusesAnEditionTheInstanceIsNot() {
    UUID owner = user();
    assertThatThrownBy(
            () ->
                libraryService.createLibrary(
                    confluence("falsche Edition", ConfluenceEdition.CLOUD, dataCenter.baseUrl())
                        .build(),
                    currentUser(owner)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Data Center")
        .hasMessageContaining("erkannt, nicht gewählt");
  }

  @Test
  void editionIsImmutableButTheSelectionIsNot() {
    UUID owner = user();
    CurrentUser caller = currentUser(owner);
    LibraryDetail created =
        libraryService.createLibrary(
            confluence("Wiki", ConfluenceEdition.DATA_CENTER, dataCenter.baseUrl()).build(),
            caller);
    UUID libraryId = created.library().getId();

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    libraryId,
                    libraryUpdate("Wiki").confluenceEdition(ConfluenceEdition.CLOUD).build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("confluenceEdition kann nach dem Anlegen");

    // echoing the stored edition is fine, like sourceType
    libraryService.updateLibrary(
        libraryId,
        libraryUpdate("Wiki").confluenceEdition(ConfluenceEdition.DATA_CENTER).build(),
        caller);

    // ADR-0023, Entscheidung 4: a changed selection discards the run state, so the next run is a
    // full one; a rename leaves it alone
    syncStateRepository.save(new ConfluenceSyncState(libraryId));
    LibraryDetail updated =
        libraryService.updateLibrary(
            libraryId,
            libraryUpdate("Wiki")
                .confluenceSpaces(
                    List.of(
                        new ConfluenceSpaceSelection("OPS", "Betrieb"),
                        new ConfluenceSpaceSelection("ENG", null)))
                .build(),
            caller);
    assertThat(syncStateRepository.findByLibraryId(libraryId)).isEmpty();
    syncStateRepository.save(new ConfluenceSyncState(libraryId));
    assertThat(updated.library().getConfluenceSpaces())
        .extracting(ConfluenceSpaceSelection::getSpaceKey)
        .containsExactly("ENG", "OPS");
    assertThat(libraryRepository.findById(libraryId).orElseThrow().getConfluenceSpaces())
        .extracting(ConfluenceSpaceSelection::getSpaceKey)
        .containsExactly("ENG", "OPS");

    // a rename leaves the selection alone - and the run state
    libraryService.updateLibrary(libraryId, libraryUpdate("Wiki umbenannt").build(), caller);
    assertThat(libraryRepository.findById(libraryId).orElseThrow().getConfluenceSpaces())
        .hasSize(2);
    assertThat(syncStateRepository.findByLibraryId(libraryId)).isPresent();

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    libraryId, libraryUpdate("Wiki").confluenceSpaces(List.of()).build(), caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("mindestens ein Space");
  }

  @Test
  void editionAndSpacesAreRefusedOnALibraryOfAnotherType() {
    UUID owner = user();
    CurrentUser caller = currentUser(owner);
    UUID rss =
        libraryService
            .createLibrary(
                libraryCreation("Feed", DocumentSourceType.RSS_FEED)
                    .sourceUrl(URI.create("https://example.org/feed.xml"))
                    .build(),
                caller)
            .library()
            .getId();

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    rss,
                    libraryUpdate("Feed").confluenceEdition(ConfluenceEdition.CLOUD).build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nur für sourceType CONFLUENCE zulässig");
    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    rss,
                    libraryUpdate("Feed")
                        .confluenceSpaces(List.of(new ConfluenceSpaceSelection("A", null)))
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nur für sourceType CONFLUENCE zulässig");
  }

  @Test
  void updatingTheAddressKeepsCredentialsOnTheSameOriginAndValidatesForTheStoredEdition() {
    UUID owner = user();
    CurrentUser caller = currentUser(owner);
    UUID libraryId =
        libraryService
            .createLibrary(
                confluence("Cloud", ConfluenceEdition.CLOUD, cloud.baseUrl())
                    .sourceCredentials("dienst@behoerde.example:token")
                    .build(),
                caller)
            .library()
            .getId();

    libraryService.updateLibrary(
        libraryId,
        libraryUpdate("Cloud").sourceUrl(URI.create(cloud.baseUrl() + "/wiki/")).build(),
        caller);
    KnowledgeLibrary sameOrigin = libraryRepository.findById(libraryId).orElseThrow();
    assertThat(sameOrigin.getSourceUrl()).isEqualTo(cloud.baseUrl());
    assertThat(sameOrigin.getSourceCredentials()).isEqualTo("dienst@behoerde.example:token");

    // a new host drops the stored credentials, so the update must bring valid ones
    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    libraryId,
                    libraryUpdate("Cloud")
                        .sourceUrl(URI.create(cloud.baseUrl().replace("127.0.0.1", "localhost")))
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceCredentials sind erforderlich");
    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    libraryId,
                    libraryUpdate("Cloud")
                        .sourceUrl(URI.create(cloud.baseUrl().replace("127.0.0.1", "localhost")))
                        .sourceCredentials("nur-token")
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("E-Mail");
  }

  /**
   * The epic's example (ADR-0023, Entscheidung 5): five libraries over two instances with three
   * tokens, created by two people - no uniqueness on address or token, overlapping selections
   * allowed, every library independent.
   */
  @Test
  void manyLibrariesAgainstTheSameInstanceAreIndependent() {
    CurrentUser alice = currentUser(user());
    CurrentUser bob = currentUser(user());
    String instance1 = dataCenter.baseUrl();
    String instance2 = secondDataCenter.baseUrl();

    UUID a = create(alice, "Instanz 1, Space 1", instance1, "token-1", List.of("S1"));
    UUID b = create(alice, "Instanz 1, Space 2+3", instance1, "token-1", List.of("S2", "S3"));
    UUID c = create(bob, "Instanz 1, Space 4", instance1, "token-2", List.of("S4"));
    UUID d = create(bob, "Instanz 2, Space A", instance2, "token-a", List.of("A"));
    UUID e = create(alice, "Instanz 2, Space B", instance2, "token-b", List.of("B"));
    // overlapping selection against the same instance is allowed as well (indexed twice, ADR-0023)
    UUID f = create(bob, "Instanz 1, Space 1 nochmal", instance1, "token-2", List.of("S1", "S2"));

    List<KnowledgeLibrary> all = libraryRepository.findAllById(List.of(a, b, c, d, e, f));
    assertThat(all).hasSize(6);
    assertThat(all).allMatch(l -> l.getSourceType() == DocumentSourceType.CONFLUENCE);
    assertThat(all.stream().filter(l -> l.getSourceUrl().equals(instance1))).hasSize(4);
    assertThat(libraryRepository.findById(a).orElseThrow().getConfluenceSpaces())
        .extracting(ConfluenceSpaceSelection::getSpaceKey)
        .containsExactly("S1");
    assertThat(libraryRepository.findById(f).orElseThrow().getConfluenceSpaces())
        .extracting(ConfluenceSpaceSelection::getSpaceKey)
        .containsExactly("S1", "S2");

    // changing one selection touches no other library
    libraryService.updateLibrary(
        b,
        libraryUpdate("Instanz 1, Space 2+3")
            .confluenceSpaces(List.of(new ConfluenceSpaceSelection("S3", null)))
            .build(),
        alice);
    assertThat(libraryRepository.findById(f).orElseThrow().getConfluenceSpaces()).hasSize(2);
    assertThat(libraryRepository.findById(b).orElseThrow().getConfluenceSpaces()).hasSize(1);
  }

  // ---- helpers ---------------------------------------------------------------------------------

  @Test
  void aManagerGeneratesRotatesAndRemovesTheWebhookSecretWhichIsNeverReadable() {
    // #1140: the secret is returned exactly once, stored encrypted, visible afterwards only as a
    // yes/no, and every change leaves an audit entry naming the field, never the value.
    UUID owner = user();
    UUID libraryId =
        create(currentUser(owner), "Wiki", dataCenter.baseUrl(), "pat", List.of("ENG"));
    assertThat(
            libraryService
                .getLibrary(libraryId, currentUser(owner))
                .managementDetail()
                .confluenceWebhookSecretSet())
        .isFalse();

    String first = libraryService.generateConfluenceWebhookSecret(libraryId, currentUser(owner));
    assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]+");
    KnowledgeLibrary stored = libraryRepository.findById(libraryId).orElseThrow();
    assertThat(stored.getConfluenceWebhookSecret()).isEqualTo(first);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT source_confluence_webhook_secret FROM knowledge_libraries WHERE id = ?",
                String.class,
                libraryId))
        .as("encrypted at rest like the credentials")
        .startsWith("enc:v1:")
        .doesNotContain(first);
    assertThat(
            libraryService
                .getLibrary(libraryId, currentUser(owner))
                .managementDetail()
                .confluenceWebhookSecretSet())
        .isTrue();

    String second = libraryService.generateConfluenceWebhookSecret(libraryId, currentUser(owner));
    assertThat(second).isNotEqualTo(first);
    assertThat(libraryRepository.findById(libraryId).orElseThrow().getConfluenceWebhookSecret())
        .isEqualTo(second);

    libraryService.removeConfluenceWebhookSecret(libraryId, currentUser(owner));
    assertThat(libraryRepository.findById(libraryId).orElseThrow().getConfluenceWebhookSecret())
        .isNull();
    libraryService.removeConfluenceWebhookSecret(libraryId, currentUser(owner));

    List<String> audit =
        jdbcTemplate.queryForList(
            "SELECT after FROM audit_log WHERE object_id = ? AND event_type = ?"
                + " ORDER BY recorded_at",
            String.class,
            libraryId.toString(),
            "LIBRARY_SOURCE_UPDATED");
    assertThat(audit)
        .as("generate, rotate, remove - the idempotent second remove writes none")
        .hasSize(3)
        .allSatisfy(
            payload ->
                assertThat(payload)
                    .contains("confluenceWebhookSecret")
                    .doesNotContain(first)
                    .doesNotContain(second));
  }

  @Test
  void theWebhookSecretTakesManagerAndOnlyExistsForConfluence() {
    UUID owner = user();
    UUID editor = user();
    UUID libraryId =
        create(currentUser(owner), "Wiki", dataCenter.baseUrl(), "pat", List.of("ENG"));
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.USER, editor, AssetRole.EDITOR),
        currentUser(owner));

    assertThatThrownBy(
            () -> libraryService.generateConfluenceWebhookSecret(libraryId, currentUser(editor)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> libraryService.removeConfluenceWebhookSecret(libraryId, currentUser(editor)))
        .isInstanceOf(AccessDeniedException.class);

    UUID upload =
        libraryService
            .createLibrary(
                libraryCreation("Ablage", DocumentSourceType.UPLOAD).build(), currentUser(owner))
            .library()
            .getId();
    assertThatThrownBy(
            () -> libraryService.generateConfluenceWebhookSecret(upload, currentUser(owner)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("CONFLUENCE");
  }

  private UUID create(
      CurrentUser caller, String name, String url, String token, List<String> spaceKeys) {
    List<ConfluenceSpaceSelection> spaces =
        spaceKeys.stream().map(k -> new ConfluenceSpaceSelection(k, null)).toList();
    return libraryService
        .createLibrary(
            confluence(name, ConfluenceEdition.DATA_CENTER, url)
                .sourceCredentials(token)
                .confluenceSpaces(spaces)
                .build(),
            caller)
        .library()
        .getId();
  }

  private static LibraryCreationBuilder confluence(
      String name, ConfluenceEdition edition, String url) {
    return libraryCreation(name, DocumentSourceType.CONFLUENCE)
        .sourceUrl(URI.create(url))
        .sourceCredentials(edition == ConfluenceEdition.CLOUD ? "a@b.example:token" : "pat-token")
        .confluenceEdition(edition)
        .confluenceSpaces(List.of(new ConfluenceSpaceSelection("ENG", "Engineering")))
        .visibility(LibraryVisibility.PRIVATE);
  }

  private UUID user() {
    User user =
        new User(
            "confluence-" + UUID.randomUUID(), "https://issuer.example", null, "Confluence Tester");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    userIds.add(id);
    return id;
  }

  private CurrentUser currentUser(UUID userId) {
    return CurrentUser.of(userId, organizationId, SystemRole.USER, "Confluence Tester");
  }
}
