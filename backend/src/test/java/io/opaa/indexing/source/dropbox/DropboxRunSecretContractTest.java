package io.opaa.indexing.source.dropbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.run.IndexingRunTemplate;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.RunSecretContract;
import io.opaa.indexing.source.ScanJournal;
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

/** The Dropbox run against {@link FakeDropboxServer}, the token being what the core hands out. */
class DropboxRunSecretContractTest extends RunSecretContract {

  private static final int FILES = 10;
  private static final String RENEWED = "sl.erneuertes-token";

  private FakeDropboxServer server;

  @BeforeEach
  void serve() {
    server = new FakeDropboxServer();
    server.alsoAccept(RENEWED);
    for (int i = 1; i <= FILES; i++) {
      server.file("/Akten/Akte " + i + ".txt", "Akte " + i);
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
        FakeDropboxServer.TOKEN,
        ConnectorData.of(Map.of("folders", List.of("/Akten"))));
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
    return "sl.widerrufen";
  }

  @Override
  protected SourceType type() {
    return DropboxSourceConnector.TYPE;
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
    new DropboxIndexingExecutor(
            new DropboxApiFactory(
                DropboxProperties.at(server.base()), TargetAddressValidator.disabled(), wait -> {}),
            ingestService,
            documentRepository,
            mock(LibraryFolderService.class),
            new ScanJournal(syncState),
            Clock.systemUTC(),
            template,
            ProductionDocumentFormats.supportedFormats())
        .execute(jobId, library, runMode());
  }
}
