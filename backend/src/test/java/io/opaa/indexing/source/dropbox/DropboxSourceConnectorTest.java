package io.opaa.indexing.source.dropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.Revocation;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SecretKind;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceCredentialsException;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The connector's own rules: OAuth with a refresh token through a required profile, the fixed
 * address, the settings it accepts, the connection test per folder, the folder listing in the own
 * folder and the team space, the account named after a consent and the original by download or
 * export.
 */
class DropboxSourceConnectorTest {

  private FakeDropboxServer server;
  private SourceSyncStateRepository syncState;
  private DropboxSourceConnector connector;

  @BeforeEach
  void setUp() {
    server = new FakeDropboxServer();
    server.file("/Akten/Bescheid.txt", "Bescheid über den Antrag.");
    server.folder("/Projekte");
    syncState = mock(SourceSyncStateRepository.class);
    connector =
        new DropboxSourceConnector(
            new DropboxApiFactory(
                DropboxProperties.at(server.base()), TargetAddressValidator.disabled(), wait -> {}),
            syncState);
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void theDescriptionSignsInByOAuthWithARefreshTokenThroughARequiredProfile() {
    var descriptor = new DropboxSourceConnector(apisOn(null), syncState).descriptor();

    assertThat(descriptor.type().key()).isEqualTo("DROPBOX");
    assertThat(descriptor.profileDeclaration().support())
        .isEqualTo(ConnectionProfileSupport.REQUIRED);
    assertThat(descriptor.profileDeclaration().address().fixed())
        .isEqualTo("https://api.dropboxapi.com");
    assertThat(descriptor.profileDeclaration().signIns())
        .singleElement()
        .satisfies(
            signIn -> {
              assertThat(signIn.method()).isEqualTo(ConnectionAuthMethod.OAUTH);
              assertThat(signIn.owners()).containsExactly(ConnectionOwnership.LIBRARY);
              OAuthAuth oauth = (OAuthAuth) signIn.details();
              assertThat(oauth.authorization())
                  .isEqualTo(
                      new Endpoint.Fixed(URI.create("https://www.dropbox.com/oauth2/authorize")));
              assertThat(oauth.token())
                  .isEqualTo(
                      new Endpoint.Fixed(URI.create("https://api.dropboxapi.com/oauth2/token")));
              assertThat(oauth.revocation())
                  .isEqualTo(
                      new Revocation.BearerPost(
                          new Endpoint.Fixed(
                              URI.create("https://api.dropboxapi.com/2/auth/token/revoke"))));
              assertThat(oauth.authorizationParams()).containsEntry("token_access_type", "offline");
              assertThat(oauth.defaultScopes()).isEqualTo(DropboxSourceConnector.SCOPES);
              assertThat(oauth.clientAuth()).isEqualTo(ClientAuthentication.CLIENT_SECRET_BASIC);
            });
    assertThat(descriptor.fullSyncInterval()).isEqualTo(java.time.Duration.ofDays(7));
  }

  @Test
  void theAddressIsFixedAndSetWhenAbsent() {
    SourceSettings validated = connector.validate(request(null, folders("/Akten")));

    assertThat(validated.sourceUrl()).isEqualTo(server.base().toString());
    assertThatThrownBy(
            () -> connector.validate(request("https://evil.example.org", folders("/Akten"))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("fest");
    assertThatThrownBy(
            () ->
                connector.validate(
                    new SourceSettings(null, null, null, null, true, folders("/Akten"))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Zertifikatsprüfung");
  }

  @Test
  void foldersAreNormalisedAndOverlappingOnesRefused() {
    SourceSettings validated =
        connector.validate(request(null, folders(" Akten/ ", "/Projekte/Bau")));

    assertThat(validated.connectorSettings().get("folders"))
        .isEqualTo(List.of("/Akten", "/Projekte/Bau"));
    assertThatThrownBy(() -> connector.validate(request(null, folders("/Akten", "/akten/2026"))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("überschneiden");
    assertThatThrownBy(() -> connector.validate(request(null, folders("/Akten/../x"))))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                connector.validate(
                    request(null, ConnectorData.of(Map.of("folders", List.of("/A"), "x", 1)))))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void theConnectionTestReportsEachFolder() {
    SourceConnectionTestResult result =
        connector.testConnection(connected(folders("/Akten", "/Fehlt")), null);

    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).contains("1 von 2 Ordnern ist");
    assertThat(connector.testConnection(connected(folders("/Akten")), null).message())
        .isEqualTo("Verbindung erfolgreich; der Ordner ist erreichbar.");
  }

  @Test
  void withoutAConsentTheTestAsksToConnectTheSource() {
    SourceConnectionTestResult result =
        connector.testConnection(request(null, folders("/Akten")), null);

    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).contains("Quelle verbinden");
  }

  @Test
  void aRefusedTokenIsTheFindingOfTheTest() {
    server.rejectToken();

    SourceConnectionTestResult result =
        connector.testConnection(connected(folders("/Akten")), null);

    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).contains("abgewiesen");
  }

  @Test
  void theListingOffersTheFoldersBelowThePathAskedFor() {
    server.folder("/Akten/2026");

    SourceListing root = connector.browse(new SourceBrowser.Query(connected(null), null));
    SourceListing below =
        connector.browse(
            new SourceBrowser.Query(connected(ConnectorData.of(Map.of("path", "/Akten"))), null));

    assertThat(root.entries())
        .extracting(SourceListing.Entry::key)
        .containsExactly("/Akten", "/Projekte");
    assertThat(below.entries()).extracting(SourceListing.Entry::key).containsExactly("/Akten/2026");
  }

  @Test
  void theTeamSpaceIsListedFromItsRootAndRefusedWithoutOne() {
    SourceListing without =
        connector.browse(
            new SourceBrowser.Query(connected(ConnectorData.of(Map.of("teamSpace", true))), null));
    server.withTeamSpace();
    server.folder(FakeDropboxServer.TEAM_ROOT, "/Team Akten");

    SourceListing team =
        connector.browse(
            new SourceBrowser.Query(connected(ConnectorData.of(Map.of("teamSpace", true))), null));

    assertThat(without.complete()).isFalse();
    assertThat(without.message()).contains("Team-Bereich");
    assertThat(team.entries()).extracting(SourceListing.Entry::key).containsExactly("/Team Akten");
  }

  @Test
  void theConnectedAccountIsTheAccountsAddressAndAnUnansweredLookupNamesNone() {
    assertThat(connector.connectedAccount(connected(null))).contains(FakeDropboxServer.ACCOUNT);
    assertThat(connector.connectedAccount(request(null, null))).isEmpty();

    server.rejectToken();

    assertThatThrownBy(() -> connector.connectedAccount(connected(null)))
        .isInstanceOf(SourceCredentialsException.class);
  }

  @Test
  void anOriginalIsDownloadedOnlyWithinTheFoldersAndAMissingOneIsNone() throws Exception {
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    SourceSettings settings = connected(folders("/Akten"));

    Optional<DocumentContent> found =
        connector.openOriginal(document("/akten/bescheid.txt"), library, settings);
    Optional<DocumentContent> outside =
        connector.openOriginal(document("/projekte/x.txt"), library, settings);
    Optional<DocumentContent> missing =
        connector.openOriginal(document("/akten/fehlt.txt"), library, settings);

    assertThat(found).isPresent();
    try (InputStream stream = found.get().stream()) {
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("Bescheid über den Antrag.");
    }
    assertThat(outside).isEmpty();
    assertThat(missing).isEmpty();
  }

  @Test
  void aChangedFolderListOrTeamSpaceDiscardsTheRunState() {
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    UUID id = UUID.randomUUID();
    when(library.getId()).thenReturn(id);

    connector.onSourceChanged(library, false, Set.of("fullSyncIntervalDays"));
    verify(syncState, never()).deleteByLibraryId(id);
    connector.onSourceChanged(library, false, Set.of("dropboxFolders"));
    connector.onSourceChanged(library, false, Set.of("dropboxTeamSpace"));

    verify(syncState, org.mockito.Mockito.times(2)).deleteByLibraryId(id);
  }

  private static DropboxApiFactory apisOn(URI base) {
    return new DropboxApiFactory(
        base == null ? DropboxProperties.defaults() : DropboxProperties.at(base),
        TargetAddressValidator.disabled(),
        wait -> {});
  }

  private static Document document(String pathLower) {
    Document document = mock(Document.class);
    when(document.getFilePath()).thenReturn(DropboxFileStore.OPEN_PREFIX + pathLower);
    when(document.getFileName()).thenReturn("Bescheid.txt");
    when(document.getContentType()).thenReturn("text/plain");
    when(document.getId()).thenReturn(UUID.randomUUID());
    return document;
  }

  private SourceSettings connected(ConnectorData settings) {
    return request(null, settings)
        .withCredentials(new Secret(SecretKind.ACCESS_TOKEN, FakeDropboxServer.TOKEN));
  }

  private static SourceSettings request(String url, ConnectorData settings) {
    return new SourceSettings(null, url, null, null, false, settings);
  }

  private static ConnectorData folders(String... folders) {
    return ConnectorData.of(Map.of("folders", List.of(folders)));
  }
}
