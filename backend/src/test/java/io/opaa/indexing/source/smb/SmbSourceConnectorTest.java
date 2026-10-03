package io.opaa.indexing.source.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

class SmbSourceConnectorTest {

  private static final SmbSourceConnector CONNECTOR =
      new SmbSourceConnector(
          SmbProperties.defaults(),
          TargetAddressValidator.disabled(),
          mock(SourceSyncStateRepository.class));

  private static SourceSettings settings(String url, String credentials) {
    return new SourceSettings(null, url, null, credentials, false, null);
  }

  @Nested
  class Validation {

    @Test
    void aNewLibraryIsStoredWithTheNormalisedAddressAndTheWholeShare() {
      SourceSettings validated =
          CONNECTOR.validate(settings("SMB://FileServer/Daten/", "RATHAUS\\svc:geheim"));

      assertThat(validated.sourceUrl()).isEqualTo("smb://fileserver/Daten");
      assertThat(validated.connectorSettings().get("folders")).isEqualTo(List.of("/"));
      assertThat(CONNECTOR.normalizeSourceUrl("\\\\FileServer\\Daten"))
          .isEqualTo("smb://fileserver/Daten");
    }

    @Test
    void whatASharedoesNotUseIsRefusedRatherThanIgnored() {
      assertThatThrownBy(
              () ->
                  CONNECTOR.validate(
                      new SourceSettings(null, "smb://fs/Daten", "proxy:3128", "a:b", false, null)))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("sourceProxy");
      assertThatThrownBy(
              () ->
                  CONNECTOR.validate(
                      new SourceSettings(null, "smb://fs/Daten", null, "a:b", true, null)))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("sourceInsecureSsl");
      assertThatThrownBy(
              () ->
                  CONNECTOR.validate(
                      new SourceSettings("/srv", "smb://fs/Daten", null, "a:b", false, null)))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("sourcePath");
    }

    @Test
    void credentialsAreRequiredInTheirFormat() {
      assertThatThrownBy(() -> CONNECTOR.validate(settings("smb://fs/Daten", null)))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("sourceCredentials");
      assertThatThrownBy(() -> CONNECTOR.validate(settings("smb://fs/Daten", "nur-ein-name")))
          .isInstanceOf(ValidationException.class)
          .hasMessage(SmbCredentials.FORMAT);
    }

    @Test
    void anAddressInABlockedRangeIsRefusedNamingTheAllowlist() {
      SmbSourceConnector guarded =
          new SmbSourceConnector(
              SmbProperties.defaults(),
              new TargetAddressValidator(true, List.of()),
              mock(SourceSyncStateRepository.class));

      assertThatThrownBy(() -> guarded.validate(settings("smb://127.0.0.1/Daten", "a:b")))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
      assertThat(guarded.testConnection(settings("smb://10.1.2.3/Daten", "a:b"), null))
          .satisfies(
              result -> {
                assertThat(result.reachable()).isFalse();
                assertThat(result.message()).contains("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
              });
    }

    @Test
    void theDescriptorHasNoDeepLink() {
      assertThat(CONNECTOR.descriptor().deepLink()).isFalse();
      assertThat(CONNECTOR.descriptor().remote()).isTrue();
      assertThat(CONNECTOR.descriptor().indexingRun()).isTrue();
    }
  }

  @Nested
  @Testcontainers(disabledWithoutDocker = true)
  class AgainstSamba {

    private static final AtomicInteger CASES = new AtomicInteger();

    private final SambaFixture samba = SambaFixture.get();
    private final String folder = "Verbindung " + CASES.incrementAndGet();

    @Test
    void aReadableShareAndFolderPassWithTheEntryCount() {
      samba.put(folder + "/a.txt", "A.");
      samba.put(folder + "/b.txt", "B.");

      SourceConnectionTestResult result =
          CONNECTOR.testConnection(
              samba.settings(samba.credentials(), List.of("/" + folder)), null);

      assertThat(result.reachable()).isTrue();
      assertThat(result.credentialsVerified()).isTrue();
      assertThat(result.documentCount()).isEqualTo(2);
    }

    @Test
    void aWrongPasswordIsTheSignInFinding() {
      SourceConnectionTestResult result =
          CONNECTOR.testConnection(
              samba.settings(SambaFixture.DOMAIN + "\\" + SambaFixture.USER + ":falsch", List.of()),
              null);

      assertThat(result.reachable()).isFalse();
      assertThat(result.credentialsVerified()).isFalse();
      assertThat(result.message()).contains("Anmeldung").contains("abgelehnt");
    }

    @Test
    void aMissingShareIsItsOwnFinding() {
      SourceConnectionTestResult result =
          CONNECTOR.testConnection(settings(samba.url("gibt-es-nicht"), samba.credentials()), null);

      assertThat(result.reachable()).isFalse();
      assertThat(result.credentialsVerified()).isTrue();
      assertThat(result.message()).contains("Freigabe „gibt-es-nicht“ gibt es");
    }

    @Test
    void aShareTheAccountMayNotOpenIsItsOwnFinding() {
      SourceConnectionTestResult result =
          CONNECTOR.testConnection(
              settings(samba.url(SambaFixture.LOCKED_SHARE), samba.credentials()), null);

      assertThat(result.reachable()).isFalse();
      assertThat(result.credentialsVerified()).isTrue();
      assertThat(result.message()).contains("Freigabe „gesperrt“").contains("nicht lesen");
    }

    @Test
    void aMissingAndAnUnreadableFolderAreNamed() {
      samba.mkdirs(folder + "/geheim");
      samba.denyListing(folder + "/geheim");

      SourceConnectionTestResult result =
          CONNECTOR.testConnection(
              samba.settings(
                  samba.credentials(), List.of("/" + folder + "/fehlt", "/" + folder + "/geheim")),
              null);

      assertThat(result.reachable()).isFalse();
      assertThat(result.credentialsVerified()).isTrue();
      assertThat(result.message())
          .contains("gibt es nicht: /" + folder + "/fehlt")
          .contains("nicht lesen: /" + folder + "/geheim");
    }

    @Test
    void theFolderSelectionOffersTheFoldersOfTheShareRoot() {
      samba.mkdirs(folder);

      SourceListing listing =
          CONNECTOR.browse(
              new SourceBrowser.Query(
                  settings(samba.url(SambaFixture.SHARE), samba.credentials()), null));

      assertThat(listing.complete()).isTrue();
      assertThat(listing.entries()).contains(new SourceListing.Entry("/" + folder, folder));
    }

    @Test
    void theOriginalIsReadFromItsPlaceAndOnlyWithinTheConfiguredFolders() throws Exception {
      samba.put(folder + "/Ablage/Bescheid ä.txt", "Inhalt des Bescheids.");
      samba.put("Außerhalb " + folder + ".txt", "Nicht freigegeben.");
      SourceSettings configured = samba.settings(samba.credentials(), List.of("/" + folder));

      Optional<DocumentContent> original =
          CONNECTOR.openOriginal(
              document(folder + "/Ablage/Bescheid ä.txt", "Bescheid ä.txt"), library(), configured);
      assertThat(original).isPresent();
      try (InputStream in = original.get().stream()) {
        assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo("Inhalt des Bescheids.");
      }

      assertThat(
              CONNECTOR.openOriginal(
                  document("Außerhalb " + folder + ".txt", "x.txt"), library(), configured))
          .isEmpty();
      assertThat(
              CONNECTOR.openOriginal(
                  document(folder + "/fehlt.txt", "fehlt.txt"), library(), configured))
          .isEmpty();
      assertThat(
              CONNECTOR.openOriginal(
                  document(folder + "/../Außerhalb " + folder + ".txt", "x.txt"),
                  library(),
                  configured))
          .isEmpty();
    }

    private Document document(String relative, String fileName) {
      Document document = mock(Document.class);
      when(document.getFilePath()).thenReturn(samba.url(SambaFixture.SHARE) + "/" + relative);
      when(document.getFileName()).thenReturn(fileName);
      when(document.getId()).thenReturn(UUID.randomUUID());
      return document;
    }

    private KnowledgeLibrary library() {
      KnowledgeLibrary library = mock(KnowledgeLibrary.class);
      when(library.getId()).thenReturn(UUID.randomUUID());
      return library;
    }
  }

  @Test
  void theFoldersAreTheComparableStateAndAChangeDropsTheSyncState() {
    SourceSyncStateRepository repository = mock(SourceSyncStateRepository.class);
    SmbSourceConnector connector =
        new SmbSourceConnector(
            SmbProperties.defaults(), TargetAddressValidator.disabled(), repository);
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    UUID id = UUID.randomUUID();
    when(library.getId()).thenReturn(id);

    assertThat(
            connector.settingsState(
                library, ConnectorData.of(Map.of("folders", List.of("/Akten")))))
        .isEqualTo(Map.of("smbFolders", List.of("/Akten")));
    connector.onSourceChanged(library, false, java.util.Set.of("smbFolders"));

    org.mockito.Mockito.verify(repository).deleteByLibraryId(id);
  }
}
