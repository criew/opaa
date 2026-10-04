package io.opaa.indexing.source.googledrive;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.RunSecretContract;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.ProductionDocumentFormats;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * The Google Drive run against {@link FakeDriveServer}, the token being what the core hands out.
 */
class GoogleDriveRunSecretContractTest extends RunSecretContract {

  private static final int FILES = 6;

  private FakeDriveServer server;

  @BeforeEach
  void serve() {
    server = new FakeDriveServer();
    server.addDrive("drive0", "Ablage");
    server.folder("akten", "Akten", "drive0", "drive0");
    for (int i = 1; i <= FILES; i++) {
      server.file("akte" + i, "Akte " + i + ".txt", "text/plain", "akten", "drive0", "Akte " + i);
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
        server.base().toString(),
        null,
        FakeDriveServer.TOKEN,
        false,
        ConnectorData.of(Map.of("scopes", List.of(Map.of("drive", "drive0")))));
  }

  @Override
  protected SourceType type() {
    return GoogleDriveSourceConnector.TYPE;
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
    new GoogleDriveIndexingExecutor(
            new DriveApiFactory(
                GoogleDriveProperties.defaults(), TargetAddressValidator.disabled(), wait -> {}),
            ingestService,
            documentRepository,
            mock(LibraryFolderService.class),
            cleanupService,
            syncState,
            Clock.systemUTC(),
            template,
            ProductionDocumentFormats.supportedFormats())
        .execute(jobId, library, runMode());
  }
}
