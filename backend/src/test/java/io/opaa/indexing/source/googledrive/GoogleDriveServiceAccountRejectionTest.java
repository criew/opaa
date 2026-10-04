package io.opaa.indexing.source.googledrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.FakeTokenEndpoint;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.LibrarySourceConnectionResolver;
import io.opaa.indexing.source.ScanJournal;
import io.opaa.indexing.source.ServiceAccountKey;
import io.opaa.indexing.source.ServiceAccountKeyFixture;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.ProductionDocumentFormats;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drive signed in with a service account key: a token Drive rejects is dropped from the core's
 * token cache, so asking again signs a new one, and the rejected request is repeated exactly once.
 */
class GoogleDriveServiceAccountRejectionTest {

  private static final String REJECTED = "ya29.zurueckgezogen";

  private final IndexingJobService jobService = mock(IndexingJobService.class);
  private final DocumentIngestService ingestService = mock(DocumentIngestService.class);
  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private FakeDriveServer drive;
  private FakeTokenEndpoint endpoint;

  @BeforeEach
  void serve() throws Exception {
    drive = new FakeDriveServer();
    drive.addDrive("drive0", "Ablage");
    drive.folder("akten", "Akten", "drive0", "drive0");
    drive.file("akte1", "Akte 1.txt", "text/plain", "akten", "drive0", "Akte 1");
    endpoint = new FakeTokenEndpoint();
    endpoint.answer(
        FakeTokenEndpoint.token(REJECTED, 3600),
        FakeTokenEndpoint.token(FakeDriveServer.TOKEN, 3600));
    when(ingestService.ingest(any(), any())).thenReturn(DocumentIngestResult.PROCESSED);
  }

  @AfterEach
  void stop() {
    drive.close();
    endpoint.close();
  }

  @Test
  void aRejectedTokenIsSignedAnewAndTheRequestRepeatedOnce() throws Exception {
    ServiceAccountTokens tokens =
        new ServiceAccountTokens(TargetAddressValidator.disabled(), Clock.systemUTC());
    SourceSyncStateRepository syncState = mock(SourceSyncStateRepository.class);
    when(syncState.findByLibraryId(any())).thenReturn(Optional.empty());
    when(syncState.save(any())).thenAnswer(call -> call.getArgument(0));
    DriveApiFactory apis =
        new DriveApiFactory(
            GoogleDriveProperties.defaults(), TargetAddressValidator.disabled(), wait -> {});
    GoogleDriveSourceConnector connector =
        new GoogleDriveSourceConnector(drive.base(), endpoint.uri(), apis, syncState);
    IndexingRunTemplate template =
        new IndexingRunTemplate(
            jobService,
            mock(IndexingRunEventRepository.class),
            mock(StaleDocumentCleanupService.class),
            documentRepository,
            mock(LibraryStorageQuotaService.class),
            new LibrarySourceConnectionResolver(
                type ->
                    type.equals(GoogleDriveSourceConnector.TYPE)
                        ? Optional.of(connector)
                        : Optional.empty(),
                tokens),
            Clock.systemUTC(),
            tokens);
    UUID jobId = UUID.randomUUID();

    new GoogleDriveIndexingExecutor(
            apis,
            ingestService,
            documentRepository,
            mock(LibraryFolderService.class),
            mock(StaleDocumentCleanupService.class),
            new ScanJournal(syncState),
            Clock.systemUTC(),
            template,
            ProductionDocumentFormats.supportedFormats())
        .execute(jobId, library(), IndexingRunMode.FULL);

    verify(jobService, never()).failJob(eq(jobId), anyString());
    verify(jobService).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
    assertThat(endpoint.forms()).as("a new token was signed after the rejection").hasSize(2);
    assertThat(Collections.frequency(drive.tokens(), REJECTED))
        .as("requests sent with the rejected token")
        .isEqualTo(1);
    assertThat(drive.tokens()).contains(FakeDriveServer.TOKEN);
  }

  private KnowledgeLibrary library() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Ablage",
            null,
            UUID.randomUUID(),
            GoogleDriveSourceConnector.TYPE,
            null,
            drive.base().toString(),
            null,
            ServiceAccountKey.parse(new ServiceAccountKeyFixture().json()).storedForm(),
            false);
    library.updateSourceSettings(
        ConnectorData.of(Map.of("scopes", List.of(Map.of("drive", "drive0")))).toJson());
    return library;
  }
}
