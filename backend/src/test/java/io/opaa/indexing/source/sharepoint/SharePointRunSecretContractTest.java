package io.opaa.indexing.source.sharepoint;

import static io.opaa.indexing.source.sharepoint.SharePointTestStores.DRIVE_0;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.RunSecretContract;
import io.opaa.indexing.source.ScanJournal;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceType;
import io.opaa.msgraph.FakeGraphServer;
import io.opaa.test.ProductionDocumentFormats;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * The SharePoint run against the {@link FakeGraphServer}, the access token being what the core
 * hands out for the profile's client credentials.
 */
class SharePointRunSecretContractTest extends RunSecretContract {

  private static final int FILES = 10;
  private static final String RENEWED = "eyJ0eXAiOiJKV1QifQ.erneuert";

  private FakeGraphServer server;

  @BeforeEach
  void serve() {
    server = new FakeGraphServer();
    server.alsoAccept(RENEWED);
    SharePointTestStores.twoLibraries(server);
    server.folder(DRIVE_0, "akten", "Akten", FakeGraphServer.rootId(DRIVE_0));
    for (int i = 1; i <= FILES; i++) {
      server.file(
          DRIVE_0,
          "akte" + i,
          "Akte " + i + ".txt",
          "akten",
          ("Akte " + i).getBytes(StandardCharsets.UTF_8));
    }
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Override
  protected SourceSettings settings() {
    return new SourceSettings(
        null,
        server.origin().toString(),
        null,
        FakeGraphServer.TOKEN,
        false,
        ConnectorData.of(Map.of("libraries", List.of(Map.of("driveId", DRIVE_0)))));
  }

  @Override
  protected String renewedSecret() {
    return RENEWED;
  }

  @Override
  protected boolean sawRenewedSecret() {
    return server.tokens().contains(RENEWED);
  }

  @Override
  protected boolean usesRejectionSeam() {
    return true;
  }

  @Override
  protected String refusedSecret() {
    return "eyJ0eXAiOiJKV1QifQ.widerrufen";
  }

  @Override
  protected SourceType type() {
    return SharePointSourceConnector.TYPE;
  }

  @Override
  protected int documents() {
    return FILES;
  }

  @Override
  protected void run(IndexingRunTemplate template, UUID jobId, KnowledgeLibrary library) {
    SourceSyncStateRepository syncState = mock(SourceSyncStateRepository.class);
    when(syncState.findByLibraryId(any())).thenReturn(Optional.empty());
    when(syncState.save(any())).thenAnswer(call -> call.getArgument(0));
    new SharePointIndexingExecutor(
            SharePointTestStores.connections(server),
            ingestService,
            documentRepository,
            mock(LibraryFolderService.class),
            cleanupService,
            new ScanJournal(syncState),
            Clock.systemUTC(),
            template,
            ProductionDocumentFormats.supportedFormats())
        .execute(jobId, library, runMode());
  }
}
