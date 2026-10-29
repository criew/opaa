package io.opaa.indexing.source.sharepoint;

import static io.opaa.indexing.source.sharepoint.SharePointTestStores.DRIVE_0;
import static io.opaa.indexing.source.sharepoint.SharePointTestStores.DRIVE_1;
import static io.opaa.indexing.source.sharepoint.SharePointTestStores.SITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.opaa.common.ValidationException;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.RequestBudget;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncState;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.msgraph.FakeGraphServer;
import io.opaa.msgraph.GraphClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * "Alle Dokumentbibliotheken einer Site": an entry with the site and no drive stands for every
 * document library the site has when a run starts.
 */
class SharePointWholeSiteTest {

  private FakeGraphServer server;
  private SharePointSourceConnector connector;

  @BeforeEach
  void setUp() {
    server = new FakeGraphServer();
    SharePointTestStores.twoLibraries(server);
    server.drive(SITE, "b!onedrive", "OneDrive", "business");
    connector =
        new SharePointSourceConnector(
            server.origin(),
            "https://login.example.org/{tenant}/oauth2/v2.0/token",
            SharePointTestStores.connections(server),
            mock(SourceSyncStateRepository.class));
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private static ConnectorData wholeSite() {
    return ConnectorData.of(
        Map.of("libraries", List.of(Map.of("site", Map.of("id", SITE, "name", "Team")))));
  }

  // regression guard for #3117: the site level was refused, every library had to be chosen alone
  @Test
  void anEntryWithTheSiteAndNoDriveIsAccepted() {
    ConnectorData read = connector.readSettings(wholeSite());

    assertThat(read.asMap())
        .containsEntry("libraries", List.of(Map.of("site", Map.of("id", SITE, "name", "Team"))));
  }

  @Test
  void theConnectionTestCountsTheDocumentLibrariesOfTheSite() {
    SourceConnectionTestResult result =
        connector.testConnection(
            new SourceSettings(
                null, server.origin().toString(), FakeGraphServer.TOKEN, wholeSite()),
            null);

    assertThat(result.reachable()).isTrue();
    assertThat(result.message()).contains("2 Dokumentbibliotheken");
  }

  @Test
  void theResolutionListsTheDocumentLibrariesOfTheSiteOnly() throws Exception {
    SharePointSettings settings = SharePointSettings.read(wholeSite());
    GraphClient graph =
        SharePointTestStores.connections(server)
            .open(
                new SourceSettings(null, server.origin().toString(), null, null),
                () -> FakeGraphServer.TOKEN,
                RequestBudget.unbounded());

    List<SharePointLibrary> found = SiteLibraries.resolve(graph, settings.sites());

    assertThat(found)
        .extracting(SharePointLibrary::driveId)
        .containsExactlyInAnyOrder(DRIVE_0, DRIVE_1);
    assertThat(settings.resolvedTo(found).libraries()).hasSize(2);
  }

  @Test
  void theDefaultRunModeHoldsTheLibrariesOfTheLastResolution() {
    SharePointSettings settings = SharePointSettings.read(wholeSite());
    SourceSyncState state = new SourceSyncState(UUID.randomUUID());

    assertThat(SharePointIndexingExecutor.streams(settings, state)).isNull();

    state.rememberResolvedScopes(Map.of("drive:" + DRIVE_0, "Team / Dokumente"));

    assertThat(SharePointIndexingExecutor.streams(settings, state))
        .containsExactly("drive:" + DRIVE_0);
  }

  @Test
  void aSiteTakenWholeAndOneOfItsLibrariesAreRefusedTogether() {
    Map<String, Object> one = Map.of("driveId", DRIVE_0, "site", Map.of("id", SITE));
    Map<String, Object> site = Map.of("site", Map.of("id", SITE));
    assertThatThrownBy(
            () -> connector.readSettings(ConnectorData.of(Map.of("libraries", List.of(one, site)))))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void aSiteTwiceIsRefused() {
    Map<String, Object> site = Map.of("site", Map.of("id", SITE));
    assertThatThrownBy(
            () ->
                connector.readSettings(ConnectorData.of(Map.of("libraries", List.of(site, site)))))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void switchingASiteToAllItsLibrariesDiscardsTheRunState() {
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    org.mockito.Mockito.when(library.getId()).thenReturn(UUID.randomUUID());
    ConnectorData one =
        ConnectorData.of(
            Map.of(
                "libraries",
                List.of(Map.of("driveId", DRIVE_0, "site", Map.of("id", SITE, "name", "Team")))));

    assertThat(connector.settingsState(library, wholeSite()))
        .isNotEqualTo(connector.settingsState(library, one));
  }
}
