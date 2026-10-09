package io.opaa.indexing.source.sharepoint;

import static io.opaa.indexing.source.sharepoint.SharePointTestStores.DRIVE_0;
import static io.opaa.indexing.source.sharepoint.SharePointTestStores.DRIVE_1;
import static io.opaa.indexing.source.sharepoint.SharePointTestStores.SITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.ClientCredentialsAuth;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import io.opaa.msgraph.FakeGraphServer;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The connector's own rules (ADR-0040, Nachtrag „SharePoint“): only a profile signs in, with client
 * credentials at the tenant's token endpoint, the address is fixed, the settings it accepts, the
 * connection test per document library, the listing in stages, and the original through Graph from
 * a library the settings still name.
 */
class SharePointSourceConnectorTest {

  private FakeGraphServer server;
  private SourceSyncStateRepository syncState;
  private SharePointSourceConnector connector;

  @BeforeEach
  void setUp() {
    server = new FakeGraphServer();
    SharePointTestStores.twoLibraries(server);
    server.drive(SITE, "b!onedrive", "OneDrive", "business");
    server.folder(DRIVE_0, "akten", "Akten", FakeGraphServer.rootId(DRIVE_0));
    server.file(DRIVE_0, "bericht", "Bericht.txt", "akten", bytes("Ein Bericht."));
    syncState = mock(SourceSyncStateRepository.class);
    connector =
        new SharePointSourceConnector(
            server.origin(),
            "https://login.example.org/{tenant}/oauth2/v2.0/token",
            SharePointTestStores.connections(server),
            syncState);
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void onlyAProfileSignsInWithClientCredentialsAtTheTenantsTokenEndpoint() {
    var descriptor = connector.descriptor();
    var declaration = descriptor.profileDeclaration();

    assertThat(descriptor.type().key()).isEqualTo("SHAREPOINT");
    assertThat(descriptor.deepLink()).isFalse();
    assertThat(descriptor.remote()).isTrue();
    assertThat(descriptor.fullSyncInterval()).isEqualTo(Duration.ofDays(1));
    assertThat(declaration.support()).isEqualTo(ConnectionProfileSupport.REQUIRED);
    assertThat(declaration.address().fixed()).isEqualTo(server.origin().toString());
    assertThat(declaration.requirementGap()).isNull();
    assertThat(declaration.signIns())
        .singleElement()
        .satisfies(
            signIn -> {
              assertThat(signIn.method()).isEqualTo(ConnectionAuthMethod.CLIENT_CREDENTIALS);
              ClientCredentialsAuth auth = (ClientCredentialsAuth) signIn.details();
              assertThat(auth.token().resolve("contoso.onmicrosoft.com"))
                  .isEqualTo(
                      URI.create(
                          "https://login.example.org/contoso.onmicrosoft.com/oauth2/v2.0/token"));
              assertThat(auth.defaultScope()).isEqualTo("https://graph.microsoft.com/.default");
              assertThat(auth.clientAuth()).isEqualTo(ClientAuthentication.CLIENT_SECRET_POST);
            });
  }

  @Test
  void theShippedConnectorSignsInAtEntraForGraph() {
    assertThat(SharePointSourceConnector.API_BASE)
        .isEqualTo(URI.create("https://graph.microsoft.com"));
    assertThat(SharePointSourceConnector.TOKEN_TEMPLATE)
        .isEqualTo("https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token");
  }

  @Test
  void theAddressIsFixedAndCertificateValidationStaysOn() {
    SourceSettings validated = connector.validate(request(null, libraries(DRIVE_0)));

    assertThat(validated.sourceUrl()).isEqualTo(server.origin().toString());
    assertThatThrownBy(
            () -> connector.validate(request("https://evil.example.org", libraries(DRIVE_0))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("fest");
    assertThatThrownBy(
            () ->
                connector.validate(
                    new SourceSettings(null, null, null, null, true, libraries(DRIVE_0))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Zertifikatsprüfung");
  }

  @Test
  void settingsNameOneToFiftyLibrariesEachOnceWithSafeIds() {
    assertThatThrownBy(
            () -> connector.readSettings(ConnectorData.of(Map.of("libraries", List.of()))))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> connector.readSettings(libraries("b!a/../b")))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> connector.readSettings(libraries(DRIVE_0, DRIVE_0)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nur einmal");
    assertThatThrownBy(
            () ->
                connector.readSettings(
                    ConnectorData.of(
                        Map.of(
                            "libraries",
                            List.of(Map.of("driveId", DRIVE_0, "folders", List.of("a?b")))))))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                connector.readSettings(
                    ConnectorData.of(Map.of("libraries", List.of(Map.of("drive", DRIVE_0))))))
        .isInstanceOf(ValidationException.class);

    ConnectorData accepted =
        connector.readSettings(
            ConnectorData.of(
                Map.of(
                    "libraries",
                    List.of(Map.of("driveId", DRIVE_0, "folders", List.of("akten", "akten"))),
                    "fullSyncIntervalDays",
                    3)));
    assertThat(accepted.toJson()).contains("\"folders\":[\"akten\"]").contains("3");
  }

  @Test
  void aFolderMayCarryTheNameItIsShownUnderWithoutChangingTheSelection() {
    ConnectorData named =
        connector.readSettings(
            ConnectorData.of(
                Map.of(
                    "libraries",
                    List.of(
                        Map.of(
                            "driveId",
                            DRIVE_0,
                            "folders",
                            List.of(
                                Map.of("id", "akten", "name", "Akten / 2026"), "protokolle"))))));
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    Map<String, Object> plain =
        connector.settingsState(
            library,
            ConnectorData.of(
                Map.of(
                    "libraries",
                    List.of(
                        Map.of("driveId", DRIVE_0, "folders", List.of("akten", "protokolle"))))));

    assertThat(named.toJson())
        .contains("{\"id\":\"akten\",\"name\":\"Akten / 2026\"}")
        .contains("\"protokolle\"");
    assertThat(connector.settingsState(library, named)).isEqualTo(plain);
    for (Object refused :
        List.of(
            Map.of("id", "a?b"),
            Map.of("name", "Akten"),
            Map.of("id", "akten", "name", "x".repeat(201)),
            Map.of("id", "akten", "path", "/Akten"))) {
      assertThatThrownBy(
              () ->
                  connector.readSettings(
                      ConnectorData.of(
                          Map.of(
                              "libraries",
                              List.of(Map.of("driveId", DRIVE_0, "folders", List.of(refused)))))))
          .as(refused.toString())
          .isInstanceOf(ValidationException.class);
    }
  }

  @Test
  void aChangedSelectionDiscardsTheRunStateAndAnUnchangedOneDoesNot() {
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    UUID id = UUID.randomUUID();
    org.mockito.Mockito.when(library.getId()).thenReturn(id);
    Map<String, Object> before = connector.settingsState(library, libraries(DRIVE_0));
    Map<String, Object> renamed =
        connector.settingsState(
            library,
            ConnectorData.of(
                Map.of("libraries", List.of(Map.of("driveId", DRIVE_0, "name", "Neu")))));
    Map<String, Object> filtered =
        connector.settingsState(
            library,
            ConnectorData.of(
                Map.of("libraries", List.of(Map.of("driveId", DRIVE_0, "folders", List.of("x"))))));

    assertThat(renamed).isEqualTo(before);
    assertThat(filtered).isNotEqualTo(before);

    connector.onSourceChanged(library, false, Set.of());
    verify(syncState, never()).deleteByLibraryId(id);
    connector.onSourceChanged(library, false, Set.of("sharePointLibraries"));
    verify(syncState).deleteByLibraryId(id);
  }

  @Test
  void theConnectionTestNamesEachLibraryAndRejectsOneDrive() {
    SourceConnectionTestResult result =
        connector.testConnection(withToken(libraries(DRIVE_0, "b!onedrive", "b!unbekannt")), null);

    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).contains("2 von 3");
    assertThat(result.details().toJson())
        .contains(SharePointFileStore.NOT_A_LIBRARY)
        .contains(SharePointFileStore.LIBRARY_NOT_VISIBLE);
  }

  @Test
  void theConnectionTestWithoutTokenAsksForAProfile() {
    SourceConnectionTestResult result =
        connector.testConnection(request(null, libraries(DRIVE_0)), null);

    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).contains("Zugang");
  }

  @Test
  void theListingGoesFromTheSiteToItsDocumentLibrariesToTheirFolders() {
    SourceListing byAddress =
        browse(Map.of("siteUrl", "https://contoso.sharepoint.com/sites/team/"));
    SourceListing drives = browse(Map.of("site", SITE));
    SourceListing folders = browse(Map.of("drive", DRIVE_0));
    SourceListing below = browse(Map.of("drive", DRIVE_0, "folder", "akten"));

    assertThat(byAddress.entries())
        .containsExactly(new SourceListing.Entry("site:" + SITE, "Team"));
    assertThat(drives.entries())
        .extracting(SourceListing.Entry::key)
        .containsExactlyInAnyOrder("drive:" + DRIVE_0, "drive:" + DRIVE_1);
    assertThat(folders.entries()).containsExactly(new SourceListing.Entry("folder:akten", "Akten"));
    assertThat(below.entries()).isEmpty();
    assertThat(below.complete()).isTrue();
  }

  @Test
  void theSiteSearchNeedsSitesReadAllAndOtherwiseAsksForTheAddress() {
    SourceListing found = browse(Map.of("search", "tea"));
    server.grantOnly(SITE);
    SourceListing refused = browse(Map.of("search", "tea"));
    SourceListing stillByAddress =
        browse(Map.of("siteUrl", "https://contoso.sharepoint.com/sites/team"));

    assertThat(found.entries())
        .extracting(SourceListing.Entry::key)
        .containsExactly("site:" + SITE);
    assertThat(refused.complete()).isFalse();
    assertThat(refused.message()).contains("Sites.Read.All").contains("siteUrl");
    assertThat(stillByAddress.entries()).hasSize(1);
  }

  @Test
  void aSiteAddressIsOnlyANameForGraphNeverATarget() {
    assertThat(SharePointSourceConnector.sitePath("https://Contoso.sharepoint.com/sites/team/"))
        .isEqualTo("sites/contoso.sharepoint.com:/sites/team");
    for (String refused :
        List.of(
            "http://contoso.sharepoint.com/sites/team",
            "https://user@contoso.sharepoint.com/sites/team",
            "https://contoso.sharepoint.com:444/sites/team",
            "https://contoso.sharepoint.com/sites/team?x=1",
            "https://contoso.sharepoint.com/sites/../team")) {
      assertThatThrownBy(() -> SharePointSourceConnector.sitePath(refused))
          .as(refused)
          .isInstanceOf(ValidationException.class);
    }
  }

  @Test
  void aSiteIdHasTheFormOfHostAndTwoGuids() {
    assertThat(SharePointSourceConnector.requireSiteId(SITE)).isEqualTo(SITE);
    for (String refused :
        List.of(
            "..",
            "contoso..com,2c712604-1370-44e7-a1f5-426573fda80a,2d2244c3-251a-49ea-93a8-39e1c3a060fe",
            "contoso.sharepoint.com,site-1,web-1",
            "contoso.sharepoint.com/../drives")) {
      assertThatThrownBy(() -> SharePointSourceConnector.requireSiteId(refused))
          .as(refused)
          .isInstanceOf(ValidationException.class);
    }
  }

  @Test
  void theOriginalComesThroughGraphFromAConfiguredLibraryOnly() throws Exception {
    Optional<DocumentContent> original =
        connector.openOriginal(
            document(SharePointFileStore.filePath(DRIVE_0, "bericht")),
            mock(KnowledgeLibrary.class),
            withToken(libraries(DRIVE_0)));
    Optional<DocumentContent> otherLibrary =
        connector.openOriginal(
            document(SharePointFileStore.filePath(DRIVE_1, "bericht")),
            mock(KnowledgeLibrary.class),
            withToken(libraries(DRIVE_0)));
    Optional<DocumentContent> gone =
        connector.openOriginal(
            document(SharePointFileStore.filePath(DRIVE_0, "fehlt")),
            mock(KnowledgeLibrary.class),
            withToken(libraries(DRIVE_0)));

    assertThat(original).isPresent();
    try (InputStream in = original.get().stream()) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("Ein Bericht.");
    }
    assertThat(otherLibrary).isEmpty();
    assertThat(gone).isEmpty();
    assertThat(server.downloadAuthorizations()).containsOnly("(none)");
  }

  private SourceListing browse(Map<String, Object> query) {
    return connector.browse(new SourceBrowser.Query(withToken(ConnectorData.of(query)), null));
  }

  private SourceSettings withToken(ConnectorData connectorSettings) {
    return new SourceSettings(
        null, server.origin().toString(), null, FakeGraphServer.TOKEN, false, connectorSettings);
  }

  private static SourceSettings request(String url, ConnectorData settings) {
    return new SourceSettings(null, url, null, null, false, settings);
  }

  private static ConnectorData libraries(String... drives) {
    return ConnectorData.of(
        Map.of(
            "libraries", java.util.Arrays.stream(drives).map(d -> Map.of("driveId", d)).toList()));
  }

  private static Document document(String filePath) {
    return new Document("Bericht.txt", filePath, "text/plain", 1L, SourceType.of("SHAREPOINT"));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
