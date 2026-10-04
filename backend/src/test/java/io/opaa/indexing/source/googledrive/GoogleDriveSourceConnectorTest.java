package io.opaa.indexing.source.googledrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.security.TargetAddressValidator;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The connector's own rules (ADR-0040): fixed address and sign-in kind in the description, the
 * settings it accepts, the connection test per scope, the listing of drives and shared folders, and
 * the original by export or download.
 */
class GoogleDriveSourceConnectorTest {

  private FakeDriveServer server;
  private GoogleDriveSourceConnector connector;

  @BeforeEach
  void setUp() {
    server = new FakeDriveServer();
    server.addDrive("drive0", "Ablage");
    server.folder("folder1", "Freigabe", FakeDriveServer.ROOT_ID, null).sharedWithMe = true;
    connector =
        new GoogleDriveSourceConnector(
            server.base(),
            URI.create("https://oauth2.example.org/token"),
            new DriveApiFactory(
                GoogleDriveProperties.defaults(), TargetAddressValidator.disabled(), wait -> {}),
            mock(SourceSyncStateRepository.class));
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void theDescriptionSignsInWithAServiceAccountKeyAtTheTokenEndpoint() {
    var descriptor = connector.descriptor();

    assertThat(descriptor.type().key()).isEqualTo("GOOGLE_DRIVE");
    assertThat(descriptor.deepLink()).isTrue();
    assertThat(descriptor.profileDeclaration().serviceAccountKey().tokenEndpoint())
        .isEqualTo(URI.create("https://oauth2.example.org/token"));
    assertThat(descriptor.profileDeclaration().serviceAccountKey().scope())
        .isEqualTo(GoogleDriveSourceConnector.SCOPE);
    assertThat(descriptor.profileDeclaration().admitsProfiles()).isFalse();
    assertThat(descriptor.profileDeclaration().address().fixed())
        .isEqualTo(server.base().toString());
    assertThat(descriptor.fullSyncInterval()).isEqualTo(java.time.Duration.ofDays(7));
  }

  @Test
  void theAddressIsFixedAndSetWhenAbsent() {
    SourceSettings validated = connector.validate(request(null, scopes(Map.of("drive", "d1"))));

    assertThat(validated.sourceUrl()).isEqualTo(server.base().toString());
    assertThatThrownBy(
            () ->
                connector.validate(
                    request("https://evil.example.org", scopes(Map.of("drive", "d1")))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("fest");
  }

  @Test
  void certificateValidationCannotBeTurnedOff() {
    SourceSettings insecure =
        new SourceSettings(null, null, null, null, true, scopes(Map.of("drive", "d1")));

    assertThatThrownBy(() -> connector.validate(insecure))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Zertifikatsprüfung");
  }

  @Test
  void settingsAreCheckedAndMyDriveNeedsAnImitatedAccount() {
    assertThatThrownBy(() -> connector.readSettings(ConnectorData.of(Map.of("scopes", List.of()))))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                connector.readSettings(
                    ConnectorData.of(Map.of("scopes", List.of(Map.of("folder", "a'b"))))))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                connector.readSettings(
                    ConnectorData.of(Map.of("scopes", List.of(Map.of("myDrive", true))))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("imitiertes Konto");

    ConnectorData accepted =
        connector.readSettings(
            ConnectorData.of(
                Map.of(
                    "scopes",
                    List.of(Map.of("myDrive", true), Map.of("drive", "d1")),
                    "subject",
                    " fach@example.org ")));
    assertThat(connector.assertionSubject(accepted)).isEqualTo("fach@example.org");
  }

  @Test
  void theConnectionTestReportsEachScope() {
    SourceSettings settings =
        new SourceSettings(
            null,
            server.base().toString(),
            null,
            FakeDriveServer.TOKEN,
            false,
            scopes(Map.of("drive", "drive0"), Map.of("folder", "fehlt")));

    SourceConnectionTestResult result = connector.testConnection(settings, null);

    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).contains("1 von 2");
    assertThat(result.details().toJson()).contains("drive:drive0").contains("folder:fehlt");
  }

  @Test
  void aRefusedTokenIsTheFindingOfTheTest() {
    server.rejectToken();
    SourceSettings settings =
        new SourceSettings(
            null,
            server.base().toString(),
            null,
            FakeDriveServer.TOKEN,
            false,
            scopes(Map.of("drive", "drive0")));

    SourceConnectionTestResult result = connector.testConnection(settings, null);

    assertThat(result.reachable()).isFalse();
    assertThat(result.credentialsVerified()).isFalse();
    assertThat(result.message()).contains("Zugriffstoken");
  }

  @Test
  void theListingOffersSharedDrivesAndSharedFolders() {
    SourceListing listing =
        connector.browse(
            new SourceBrowser.Query(
                new SourceSettings(
                    null, server.base().toString(), null, FakeDriveServer.TOKEN, false, null),
                null));

    assertThat(listing.complete()).isTrue();
    assertThat(listing.entries())
        .containsExactly(
            new SourceListing.Entry("drive:drive0", "Ablage"),
            new SourceListing.Entry("folder:folder1", "Freigabe"));
  }

  @Test
  void anOriginalIsExportedOrDownloadedAndAMissingOneIsNone() throws Exception {
    server.file("doc", "Protokoll", "application/vnd.google-apps.document", "folder1", null, "D");
    server.file("bin", "Plan.txt", "text/plain", "folder1", null, "Ein Plan.");
    SourceSettings settings =
        new SourceSettings(
            null, server.base().toString(), null, FakeDriveServer.TOKEN, false, null);

    Optional<DocumentContent> doc =
        connector.openOriginal(document("doc", "Protokoll.docx"), library(), settings);
    Optional<DocumentContent> bin =
        connector.openOriginal(document("bin", "Plan.txt"), library(), settings);
    Optional<DocumentContent> missing =
        connector.openOriginal(document("fehlt", "Weg.txt"), library(), settings);

    assertThat(doc).isPresent();
    assertThat(server.requests()).contains("files/doc/export");
    try (InputStream stream = bin.orElseThrow().stream()) {
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("Ein Plan.");
    }
    assertThat(missing).isEmpty();
  }

  /** A new scope - even on an existing stream - discards the run state: the next run is full. */
  @Test
  void aChangedScopeOrImitatedAccountDiscardsTheRunState() {
    SourceSyncStateRepository repository = mock(SourceSyncStateRepository.class);
    GoogleDriveSourceConnector withRepository =
        new GoogleDriveSourceConnector(
            server.base(),
            URI.create("https://oauth2.example.org/token"),
            new DriveApiFactory(
                GoogleDriveProperties.defaults(), TargetAddressValidator.disabled(), wait -> {}),
            repository);
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    java.util.UUID id = java.util.UUID.randomUUID();
    org.mockito.Mockito.when(library.getId()).thenReturn(id);

    withRepository.onSourceChanged(library, false, java.util.Set.of("googleDriveScopes"));
    withRepository.onSourceChanged(library, false, java.util.Set.of("googleDriveSubject"));
    withRepository.onSourceChanged(library, false, java.util.Set.of());

    org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(2)).deleteByLibraryId(id);
  }

  private static SourceSettings request(String url, ConnectorData settings) {
    return new SourceSettings(null, url, null, null, false, settings);
  }

  @SafeVarargs
  private static ConnectorData scopes(Map<String, ?>... scopes) {
    return ConnectorData.of(Map.of("scopes", List.of(scopes)));
  }

  private static Document document(String id, String name) {
    return new Document(
        name, DriveFileStore.OPEN_PREFIX + id, "text/plain", 1L, GoogleDriveSourceConnector.TYPE);
  }

  private static KnowledgeLibrary library() {
    return mock(KnowledgeLibrary.class);
  }
}
