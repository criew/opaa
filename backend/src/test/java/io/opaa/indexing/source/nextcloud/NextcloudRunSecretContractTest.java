package io.opaa.indexing.source.nextcloud;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.RunSecretContract;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.SourceRequestPolicy;
import io.opaa.test.ProductionDocumentFormats;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** The Nextcloud run against {@link FakeNextcloudServer} with the technical user's app password. */
class NextcloudRunSecretContractTest extends RunSecretContract {

  private static final int FILES = 10;
  private static final String RENEWED_PASSWORD = "erneuertes-app-passwort";

  private FakeNextcloudServer server;

  @BeforeEach
  void serve() throws Exception {
    server = new FakeNextcloudServer("").alsoAccept(RENEWED_PASSWORD);
    for (int i = 1; i <= FILES; i++) {
      server.put("Akten/akte-" + i + ".txt", "Akte " + i + ".");
    }
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Override
  protected SourceSettings settings() {
    return NextcloudTestStores.settings(server.baseUrl(), server.credentials(), List.of("/Akten"));
  }

  @Override
  protected String renewedSecret() {
    return FakeNextcloudServer.LOGIN + ":" + RENEWED_PASSWORD;
  }

  @Override
  protected boolean sawRenewedSecret() {
    return server.passwords().contains(RENEWED_PASSWORD);
  }

  @Override
  protected String refusedSecret() {
    return FakeNextcloudServer.LOGIN + ":falsches-app-passwort";
  }

  @Override
  protected SourceType type() {
    return NextcloudSourceConnector.TYPE;
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
    new NextcloudIndexingExecutor(
            NextcloudProperties.defaults(),
            TargetAddressValidator.disabled(),
            SourceRequestPolicy.defaults(),
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
