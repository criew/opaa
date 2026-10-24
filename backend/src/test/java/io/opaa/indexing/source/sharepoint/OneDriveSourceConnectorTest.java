package io.opaa.indexing.source.sharepoint;

import static io.opaa.indexing.source.sharepoint.OneDriveTestDrives.DRIVE_ANNA;
import static io.opaa.indexing.source.sharepoint.OneDriveTestDrives.DRIVE_BERND;
import static io.opaa.indexing.source.sharepoint.OneDriveTestDrives.DRIVE_CARLA;
import static io.opaa.indexing.source.sharepoint.OneDriveTestDrives.TOKEN_ANNA;
import static io.opaa.indexing.source.sharepoint.OneDriveTestDrives.TOKEN_BERND;
import static io.opaa.indexing.source.sharepoint.OneDriveTestDrives.TOKEN_CARLA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.indexing.spi.ClientAuthentication;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.OAuthAuth;
import io.opaa.indexing.spi.Revocation;
import io.opaa.indexing.spi.SourceBrowser;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceCredentialsException;
import io.opaa.indexing.spi.SourceListing;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
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
 * The connector's own rules (ADR-0058): only a person's connected account signs in, by OAuth at her
 * tenant with delegated {@code Files.Read}; the address is fixed; the settings name one OneDrive;
 * and listing, connection test and original read nothing but the OneDrive for Business {@code
 * me/drive} names for the token.
 */
class OneDriveSourceConnectorTest {

  private FakeGraphServer server;
  private SourceSyncStateRepository syncState;
  private OneDriveSourceConnector connector;

  @BeforeEach
  void setUp() {
    server = new FakeGraphServer();
    OneDriveTestDrives.arrange(server);
    syncState = mock(SourceSyncStateRepository.class);
    connector =
        new OneDriveSourceConnector(
            server.origin(),
            "https://login.example.org/{tenant}/oauth2/v2.0/authorize",
            "https://login.example.org/{tenant}/oauth2/v2.0/token",
            SharePointTestStores.connections(server),
            syncState);
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void onlyAPersonSignsInByOAuthAtHerTenantWithDelegatedFilesRead() {
    var descriptor = connector.descriptor();
    var declaration = descriptor.profileDeclaration();

    assertThat(descriptor.type().key()).isEqualTo("ONEDRIVE");
    assertThat(descriptor.deepLink()).isFalse();
    assertThat(descriptor.remote()).isTrue();
    assertThat(descriptor.indexingRun()).isTrue();
    assertThat(descriptor.fullSyncInterval()).isEqualTo(Duration.ofDays(1));
    assertThat(declaration.support()).isEqualTo(ConnectionProfileSupport.REQUIRED);
    assertThat(declaration.address().fixed()).isEqualTo(server.origin().toString());
    assertThat(declaration.defaults().keys()).isEmpty();
    assertThat(declaration.signIns())
        .singleElement()
        .satisfies(
            signIn -> {
              assertThat(signIn.method()).isEqualTo(ConnectionAuthMethod.OAUTH);
              assertThat(signIn.owners()).containsExactly(ConnectionOwnership.PERSON);
              OAuthAuth auth = (OAuthAuth) signIn.details();
              assertThat(auth.authorization().resolve("contoso.onmicrosoft.com"))
                  .isEqualTo(
                      URI.create(
                          "https://login.example.org/contoso.onmicrosoft.com/oauth2/v2.0/authorize"));
              assertThat(auth.token().resolve("contoso.onmicrosoft.com"))
                  .isEqualTo(
                      URI.create(
                          "https://login.example.org/contoso.onmicrosoft.com/oauth2/v2.0/token"));
              assertThat(auth.revocation()).isInstanceOf(Revocation.None.class);
              assertThat(auth.defaultScopes()).isEqualTo("Files.Read offline_access");
              assertThat(auth.authorizationParams()).isEqualTo(Map.of("prompt", "select_account"));
              assertThat(auth.clientAuth()).isEqualTo(ClientAuthentication.CLIENT_SECRET_POST);
              assertThat(auth.issuer()).isNull();
            });
  }

  @Test
  void theShippedConnectorSignsInAtEntra() {
    assertThat(OneDriveSourceConnector.AUTHORIZATION_TEMPLATE)
        .isEqualTo("https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize");
  }

  @Test
  void theAddressIsFixed() {
    SourceSettings validated =
        connector.validate(
            new SourceSettings(null, null, null, OneDriveTestDrives.drive(DRIVE_ANNA)));

    assertThat(validated.sourceUrl()).isEqualTo(server.origin().toString());
    assertThatThrownBy(
            () ->
                connector.validate(
                    new SourceSettings(
                        null,
                        "https://evil.example.org",
                        null,
                        OneDriveTestDrives.drive(DRIVE_ANNA))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("fest");
  }

  @Test
  void settingsNameExactlyOneOneDriveWithSafeIds() {
    assertThat(
            connector.readSettings(
                ConnectorData.of(
                    Map.of(
                        "drive",
                        Map.of(
                            "driveId",
                            DRIVE_ANNA,
                            "folders",
                            List.of(Map.of("id", "akten", "name", "Akten")),
                            "name",
                            "OneDrive"),
                        "fullSyncIntervalDays",
                        7))))
        .isEqualTo(
            ConnectorData.of(
                Map.of(
                    "drive",
                    Map.of(
                        "driveId",
                        DRIVE_ANNA,
                        "folders",
                        List.of(Map.of("id", "akten", "name", "Akten")),
                        "name",
                        "OneDrive"),
                    "fullSyncIntervalDays",
                    7)));
    assertThatThrownBy(() -> connector.readSettings(ConnectorData.of(Map.of())))
        .isInstanceOf(ValidationException.class)
        .hasMessage("sourceSettings.drive: das OneDrive ist erforderlich");
    assertThatThrownBy(() -> connector.readSettings(OneDriveTestDrives.drive("b!a/../b")))
        .isInstanceOf(ValidationException.class)
        .hasMessageStartingWith("sourceSettings.drive: Die ID des OneDrive oder eines Ordners");
    assertThatThrownBy(
            () ->
                connector.readSettings(
                    ConnectorData.of(Map.of("drive", List.of(Map.of("driveId", DRIVE_ANNA))))))
        .isInstanceOf(ValidationException.class)
        .hasMessageStartingWith("sourceSettings.drive: Das OneDrive ist {");
    assertThatThrownBy(
            () ->
                connector.readSettings(
                    ConnectorData.of(
                        Map.of(
                            "drive", Map.of("driveId", DRIVE_ANNA), "fullSyncIntervalDays", 0.5))))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                connector.readSettings(
                    ConnectorData.of(
                        Map.of(
                            "drive",
                            Map.of("driveId", DRIVE_ANNA),
                            "libraries",
                            List.of(Map.of("driveId", DRIVE_ANNA))))))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void aChangedSelectionDiscardsTheRunState() {
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    UUID id = UUID.randomUUID();
    org.mockito.Mockito.when(library.getId()).thenReturn(id);

    assertThat(connector.settingsState(library, OneDriveTestDrives.drive(DRIVE_ANNA)))
        .isNotEqualTo(
            connector.settingsState(
                library, OneDriveTestDrives.drive(DRIVE_ANNA, List.of("akten"))));
    connector.onSourceChanged(library, false, Set.of("fullSyncIntervalDays"));
    verify(syncState, never()).deleteByLibraryId(id);
    connector.onSourceChanged(library, false, Set.of("oneDrive"));
    verify(syncState).deleteByLibraryId(id);
  }

  // --- connected account ---------------------------------------------------------------------

  @Test
  void theConnectedAccountIsTheOwnersAddressElseHerName() {
    assertThat(connector.connectedAccount(token(TOKEN_ANNA))).contains("anna@contoso.example");
    assertThat(connector.connectedAccount(token(TOKEN_BERND))).contains("Bernd Brandt");
    assertThat(connector.connectedAccount(token(null))).isEmpty();
  }

  @Test
  void anAccountWithoutOneDriveNamesNoAccountThroughTheCoresSeam() {
    server.alsoAccept("eyJ0eXAiOiJKV1QifQ.gast");

    assertThatThrownBy(() -> connector.connectedAccount(token("eyJ0eXAiOiJKV1QifQ.gast")))
        .isInstanceOf(SourceCredentialsException.class);
  }

  // --- listing -------------------------------------------------------------------------------

  @Test
  void withoutParametersTheListingOffersTheAccountsOwnOneDriveOnly() {
    SourceListing listing = connector.browse(query(TOKEN_ANNA, Map.of()));

    assertThat(listing.complete()).isTrue();
    assertThat(listing.entries())
        .containsExactly(new SourceListing.Entry("drive:" + DRIVE_ANNA, "OneDrive"));
    assertThat(OneDriveTestDrives.touched(server, DRIVE_BERND)).isFalse();
  }

  @Test
  void theFoldersOfTheOwnOneDriveAreListedByStage() {
    SourceListing root = connector.browse(query(TOKEN_ANNA, Map.of("drive", DRIVE_ANNA)));

    assertThat(root.entries()).containsExactly(new SourceListing.Entry("folder:akten", "Akten"));
    assertThat(
            connector
                .browse(query(TOKEN_ANNA, Map.of("drive", DRIVE_ANNA, "folder", "akten")))
                .entries())
        .isEmpty();
  }

  // regression guard for ADR-0058, Entscheidung 3: a token never reads a OneDrive not its own
  @Test
  void theFoldersOfAnotherPersonsOneDriveAreNeverAsked() {
    SourceListing listing = connector.browse(query(TOKEN_ANNA, Map.of("drive", DRIVE_BERND)));

    assertThat(listing.complete()).isFalse();
    assertThat(listing.entries()).isEmpty();
    assertThat(listing.message()).isEqualTo(OwnDrive.OTHER_DRIVE);
    assertThat(OneDriveTestDrives.touched(server, DRIVE_BERND)).isFalse();
  }

  @Test
  void aPrivateMicrosoftAccountIsRefusedWithItsOwnReason() {
    SourceListing listing = connector.browse(query(TOKEN_CARLA, Map.of()));

    assertThat(listing.complete()).isFalse();
    assertThat(listing.message()).isEqualTo(OwnDrive.NOT_BUSINESS);
    assertThat(OneDriveTestDrives.touched(server, DRIVE_CARLA)).isFalse();
  }

  @Test
  void anAccountWithoutOneDriveIsToldSo() {
    server.alsoAccept("eyJ0eXAiOiJKV1QifQ.gast");

    SourceListing listing = connector.browse(query("eyJ0eXAiOiJKV1QifQ.gast", Map.of()));

    assertThat(listing.complete()).isFalse();
    assertThat(listing.message()).isEqualTo(OwnDrive.NO_DRIVE);
  }

  @Test
  void theListingNeedsAConnectedAccountAndSafeIds() {
    assertThatThrownBy(() -> connector.browse(query(null, Map.of())))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> connector.browse(query(TOKEN_ANNA, Map.of("drive", "a/../b"))))
        .isInstanceOf(ValidationException.class);
    assertThat(server.requests()).isEmpty();
  }

  // --- connection test -----------------------------------------------------------------------

  @Test
  void theConnectionTestFindsTheOwnOneDriveAndItsFolders() {
    SourceConnectionTestResult result =
        connector.testConnection(
            settings(TOKEN_ANNA, OneDriveTestDrives.drive(DRIVE_ANNA, List.of("akten"))), null);

    assertThat(result.reachable()).isTrue();
    assertThat(result.message()).isEqualTo("Anmeldung erfolgreich; das OneDrive ist erreichbar.");
  }

  // regression guard for ADR-0058, Entscheidung 3: a token never reads a OneDrive not its own
  @Test
  void theConnectionTestRefusesAnotherPersonsOneDriveWithoutAskingIt() {
    SourceConnectionTestResult result =
        connector.testConnection(settings(TOKEN_ANNA, OneDriveTestDrives.drive(DRIVE_BERND)), null);

    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).isEqualTo(OwnDrive.OTHER_DRIVE);
    assertThat(OneDriveTestDrives.touched(server, DRIVE_BERND)).isFalse();
  }

  @Test
  void theConnectionTestRefusesAPrivateMicrosoftAccount() {
    SourceConnectionTestResult result =
        connector.testConnection(
            settings(TOKEN_CARLA, OneDriveTestDrives.drive(DRIVE_CARLA)), null);

    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).isEqualTo(OwnDrive.NOT_BUSINESS);
  }

  @Test
  void theConnectionTestNamesAMissingFolder() {
    SourceConnectionTestResult result =
        connector.testConnection(
            settings(TOKEN_ANNA, OneDriveTestDrives.drive(DRIVE_ANNA, List.of("weg"))), null);

    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).isEqualTo(DriveRule.ONEDRIVE.folderMissing("weg"));
  }

  @Test
  void theConnectionTestNeedsAnAccountAndADrive() {
    assertThat(connector.testConnection(settings(null, OneDriveTestDrives.drive(DRIVE_ANNA)), null))
        .extracting(SourceConnectionTestResult::reachable)
        .isEqualTo(false);
    assertThat(connector.testConnection(settings(TOKEN_ANNA, null), null).message())
        .isEqualTo("Bitte das OneDrive wählen.");
    assertThat(server.requests()).isEmpty();
  }

  // --- original ------------------------------------------------------------------------------

  @Test
  void theOriginalComesFromTheOwnOneDrive() throws Exception {
    Optional<DocumentContent> original =
        connector.openOriginal(
            document(DRIVE_ANNA, "bericht"),
            mock(KnowledgeLibrary.class),
            settings(TOKEN_ANNA, OneDriveTestDrives.drive(DRIVE_ANNA)));

    assertThat(original).isPresent();
    try (InputStream stream = original.get().stream()) {
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("Ein Bericht.");
    }
  }

  // regression guard for ADR-0058, Entscheidung 3: a token never reads a OneDrive not its own
  @Test
  void noOriginalComesFromAOneDriveTheTokenDoesNotOwn() {
    Optional<DocumentContent> original =
        connector.openOriginal(
            document(DRIVE_BERND, "geheim"),
            mock(KnowledgeLibrary.class),
            settings(TOKEN_ANNA, OneDriveTestDrives.drive(DRIVE_BERND)));

    assertThat(original).isEmpty();
    assertThat(OneDriveTestDrives.touched(server, DRIVE_BERND)).isFalse();
  }

  @Test
  void noOriginalForAnotherKindOfIdentityOrADriveTheSettingsNoLongerName() {
    assertThat(
            connector.openOriginal(
                documentAt(DriveRule.SHAREPOINT.filePath(DRIVE_ANNA, "bericht")),
                mock(KnowledgeLibrary.class),
                settings(TOKEN_ANNA, OneDriveTestDrives.drive(DRIVE_ANNA))))
        .isEmpty();
    assertThat(
            connector.openOriginal(
                document(DRIVE_ANNA, "bericht"),
                mock(KnowledgeLibrary.class),
                settings(TOKEN_ANNA, OneDriveTestDrives.drive(DRIVE_BERND))))
        .isEmpty();
    assertThat(server.requests()).isEmpty();
  }

  // --- helpers -------------------------------------------------------------------------------

  private SourceSettings settings(String token, ConnectorData drive) {
    return new SourceSettings(null, server.origin().toString(), token, drive);
  }

  private SourceSettings token(String token) {
    return settings(token, null);
  }

  private SourceBrowser.Query query(String token, Map<String, Object> asked) {
    return new SourceBrowser.Query(settings(token, ConnectorData.of(asked)), null);
  }

  private static Document document(String driveId, String itemId) {
    return documentAt(DriveRule.ONEDRIVE.filePath(driveId, itemId));
  }

  private static Document documentAt(String filePath) {
    return new Document("Bericht.txt", filePath, "text/plain", 1L, SourceType.of("ONEDRIVE"));
  }
}
