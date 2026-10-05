package io.opaa.indexing.source.nextcloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.PersonalSecretForm;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The connector half outside a run against {@link FakeNextcloudServer}: validation, connection
 * test, folder selection and the original of an indexed file.
 */
class NextcloudSourceConnectorTest {

  private FakeNextcloudServer server;
  private final SourceSyncStateRepository syncStateRepository =
      mock(SourceSyncStateRepository.class);
  private NextcloudSourceConnector connector;

  @BeforeEach
  void setUp() throws Exception {
    server = new FakeNextcloudServer("/cloud");
    connector =
        new NextcloudSourceConnector(
            NextcloudProperties.defaults(),
            TargetAddressValidator.disabled(),
            SourceRequestPolicy.defaults(),
            syncStateRepository);
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private SourceSettings settings(List<String> folders) {
    return NextcloudTestStores.settings(
        server.baseUrl() + "/remote.php/dav/files/techniker/", server.credentials(), folders);
  }

  @Test
  void validationStoresTheNormalisedAddressAndFolders() {
    SourceSettings validated = connector.validate(settings(List.of("Projekte/")));

    assertThat(validated.sourceUrl()).isEqualTo(server.baseUrl());
    assertThat(validated.connectorSettings())
        .isEqualTo(ConnectorData.of(Map.of("folders", List.of("/Projekte"))));
  }

  @Test
  void validationRequiresCredentialsWithAUserNameAndRefusesAPath() {
    SourceSettings withoutCredentials =
        NextcloudTestStores.settings(server.baseUrl(), null, List.of("/"));
    assertThatThrownBy(() -> connector.validate(withoutCredentials))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("App-Passwort");
    SourceSettings withoutUser =
        NextcloudTestStores.settings(server.baseUrl(), "nur-ein-passwort", List.of("/"));
    assertThatThrownBy(() -> connector.validate(withoutUser))
        .isInstanceOf(ValidationException.class);
    SourceSettings withPath =
        new SourceSettings("/srv", server.baseUrl(), null, server.credentials(), false, null);
    assertThatThrownBy(() -> connector.validate(withPath))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourcePath");
  }

  @Test
  void aBlockedTargetIsRefusedBeforeAnythingIsStored() {
    NextcloudSourceConnector guarded =
        new NextcloudSourceConnector(
            NextcloudProperties.defaults(),
            new TargetAddressValidator(true, List.of()),
            SourceRequestPolicy.defaults(),
            syncStateRepository);

    assertThatThrownBy(() -> guarded.validate(settings(List.of("/"))))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void theConnectionTestSignsInAndListsTheFolders() {
    server.put("Projekte/a.txt", "A.").put("Projekte/b.txt", "B.");

    SourceConnectionTestResult result =
        connector.testConnection(settings(List.of("/Projekte")), null);

    assertThat(result.reachable()).isTrue();
    assertThat(result.credentialsVerified()).isTrue();
    assertThat(result.documentCount()).isEqualTo(2);
  }

  @Test
  void theConnectionTestNamesRefusedCredentialsAndMissingFolders() {
    server.mkdir("Projekte");
    SourceConnectionTestResult missing =
        connector.testConnection(settings(List.of("/Projekte", "/Fehlt")), null);
    assertThat(missing.reachable()).isFalse();
    assertThat(missing.message())
        .contains("/Fehlt")
        .doesNotContain("/Projekte,")
        .doesNotContain("technischen Nutzer");

    server.rejectCredentials();
    SourceConnectionTestResult refused = connector.testConnection(settings(List.of("/")), null);
    assertThat(refused.reachable()).isFalse();
    assertThat(refused.credentialsVerified()).isFalse();
    assertThat(refused.message()).contains("401");
  }

  /** One sign-in for both owners: a library's app password and a person's own (#2167). */
  @Test
  void anAppPasswordSignsInForALibraryAndForAPerson() {
    SignIn signIn =
        connector
            .descriptor()
            .profileDeclaration()
            .signIn(ConnectionAuthMethod.PERSONAL_SECRET)
            .orElseThrow();

    assertThat(signIn.owners())
        .containsExactlyInAnyOrder(ConnectionOwnership.LIBRARY, ConnectionOwnership.PERSON);
    assertThat(signIn.secretForm()).isEqualTo(PersonalSecretForm.USERNAME_AND_PASSWORD);
  }

  @Test
  void theFolderSelectionOffersTheFoldersAtTheUsersRoot() {
    server.mkdir("Projekte").mkdir("Archiv").put("lose.txt", "Lose.");

    SourceListing listing = connector.browse(new SourceBrowser.Query(settings(List.of("/")), null));

    assertThat(listing.complete()).isTrue();
    assertThat(listing.entries())
        .extracting(SourceListing.Entry::key)
        .containsExactlyInAnyOrder("/Projekte", "/Archiv");
  }

  @Test
  void theOriginalIsServedFromItsRecordedPlaceWhenItsFileIdStillMatches() throws Exception {
    server.put("Projekte/Akten/a.txt", "Original.");
    Document document = document("Projekte/Akten/a.txt", "/Projekte", "Akten", "a.txt");

    Optional<DocumentContent> original =
        connector.openOriginal(
            document, library(), connector.validate(settings(List.of("/Projekte"))));

    assertThat(original).isPresent();
    try (InputStream stream = original.get().stream()) {
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("Original.");
    }
  }

  @Test
  void aMovedFileOrOneOutsideTheFoldersHasNoOriginal() {
    server.put("Projekte/Akten/a.txt", "Original.");
    Document document = document("Projekte/Akten/a.txt", "/Projekte", "Akten", "a.txt");
    SourceSettings validated = connector.validate(settings(List.of("/Projekte")));
    server.put("Projekte/Akten/b.txt", "Anderes.").move("Projekte/Akten/a.txt", "Projekte/x.txt");
    server.move("Projekte/Akten/b.txt", "Projekte/Akten/a.txt");

    assertThat(connector.openOriginal(document, library(), validated))
        .as("another file now at the recorded place")
        .isEmpty();
    SourceSettings narrowed = connector.validate(settings(List.of("/Archiv")));
    assertThat(connector.openOriginal(document, library(), narrowed)).isEmpty();
  }

  @Test
  void aChangedAddressOrFolderSelectionDiscardsTheSyncState() {
    KnowledgeLibrary library = library();

    connector.onSourceChanged(library, false, Set.of());
    verifyNoInteractions(syncStateRepository);
    connector.onSourceChanged(library, false, Set.of("nextcloudFolders"));
    verify(syncStateRepository).deleteByLibraryId(library.getId());
  }

  private Document document(String path, String container, String hierarchy, String name) {
    Document document =
        new Document(
            name,
            server.baseUrl() + "/index.php/f/" + server.fileId(path),
            "text/plain",
            1L,
            NextcloudSourceConnector.TYPE);
    document.applySourceContext(new SourceDocumentContext(container, hierarchy));
    return document;
  }

  private KnowledgeLibrary library() {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(),
        "Ablage",
        null,
        UUID.randomUUID(),
        NextcloudSourceConnector.TYPE,
        null,
        server.baseUrl(),
        null,
        null,
        false);
  }
}
