package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.document.Document;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.TargetAddressValidator;
import io.opaa.test.ProductionDocumentFormats;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The full sync against a real object store (MinIO in a container, ADR-0027, Entscheidung 9): a
 * scope beyond one listing page is taken up completely, a second run over the same bestand
 * downloads nothing and removes what vanished, and a scope the credentials cannot list leaves the
 * bestand alone. Skipped without Docker; the CI runs it.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3IndexingExecutorMinioTest {

  private static final int MANY = 1005;

  private static MinioFixture minio;
  private static String bucket;

  private final List<DocumentIngest> ingests = new CopyOnWriteArrayList<>();
  private final List<Document> storedDocuments = new ArrayList<>();
  private DocumentIngestService documentIngestService;
  private IndexingJobService indexingJobService;
  private IndexingRunEventRepository eventRepository;
  private DocumentRepository documentRepository;
  private StaleDocumentCleanupService cleanupService;
  private LibraryFolderService folderService;
  private SourceSyncStateRepository syncStateRepository;

  @BeforeAll
  static void seed() throws Exception {
    minio = MinioFixture.get();
    bucket = minio.createBucket("opaa-vollabgleich");
    minio.putMany(bucket, "viele/", MANY, null);
    minio.putObject(bucket, "viele/", new byte[0], "application/x-directory");
    minio.putObject(bucket, "viele/sitzung.pdf", "%PDF-1.4 sitzung", "application/pdf");
    minio.putObject(bucket, "andere/nicht-im-bereich.txt", "x", "text/plain");
  }

  @BeforeEach
  void setUp() throws Exception {
    documentIngestService = mock(DocumentIngestService.class);
    when(documentIngestService.ingest(any(), any()))
        .thenAnswer(
            invocation -> {
              ingests.add(invocation.getArgument(0));
              return DocumentIngestResult.PROCESSED;
            });
    indexingJobService = mock(IndexingJobService.class);
    eventRepository = mock(IndexingRunEventRepository.class);
    documentRepository = mock(DocumentRepository.class);
    when(documentRepository.findByLibraryIdAndFilePath(any(), any()))
        .thenAnswer(
            invocation ->
                storedDocuments.stream()
                    .filter(d -> d.getFilePath().equals(invocation.getArgument(1)))
                    .findFirst());
    when(documentRepository.findByLibraryIdAndSourceType(any(), any()))
        .thenAnswer(invocation -> List.copyOf(storedDocuments));
    cleanupService =
        spy(new StaleDocumentCleanupService(documentRepository, mock(VectorChunkStore.class)));
    folderService = mock(LibraryFolderService.class);
    syncStateRepository = mock(SourceSyncStateRepository.class);
    when(syncStateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  private S3IndexingExecutor executor() {
    S3Properties properties =
        new S3Properties(0, 0, Duration.ofSeconds(10), null, null, 0, null, 0, 0);
    return new S3IndexingExecutor(
        new S3ClientFactory(properties, TargetAddressValidator.disabled()),
        properties,
        documentIngestService,
        documentRepository,
        folderService,
        cleanupService,
        syncStateRepository,
        Clock.fixed(Instant.parse("2026-09-06T20:00:00Z"), ZoneOffset.UTC),
        new IndexingRunTemplate(
            indexingJobService,
            eventRepository,
            cleanupService,
            documentRepository,
            mock(LibraryStorageQuotaService.class)),
        ProductionDocumentFormats.supportedFormats());
  }

  private static KnowledgeLibrary library(S3Credentials credentials, List<S3Scope> scopes) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Vollabgleich",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.S3,
            null,
            minio.endpoint().toString(),
            null,
            credentials.accessKey() + ":" + credentials.secretKey(),
            false);
    library.updateS3Settings(new S3SourceSettings(MinioFixture.REGION, true, scopes, null, null));
    return library;
  }

  /** What a previous run stored: one indexed row per ingest, at the ingest's own feature. */
  private void rememberAsStored(List<DocumentIngest> previous) {
    for (DocumentIngest ingest : previous) {
      Document document =
          new Document(
              ingest.fileName(), ingest.filePath(), "text/plain", 2L, DocumentSourceType.S3);
      document.setStatus(DocumentStatus.INDEXED);
      document.setLastModifiedRemote(ingest.changeMarker());
      storedDocuments.add(document);
    }
  }

  @SuppressWarnings("unchecked")
  private Set<String> reconciledPaths(KnowledgeLibrary library, S3IndexingExecutor executor) {
    ArgumentCaptor<Set<String>> current = ArgumentCaptor.forClass(Set.class);
    verify(cleanupService)
        .reconcile(
            eq(library),
            eq(DocumentSourceType.S3),
            current.capture(),
            any(),
            any(),
            eq(executor),
            eq(IndexingRunMode.FULL));
    return current.getValue();
  }

  @Test
  void takesUpAScopeBeyondOneListingPageAndSkipsItOnTheNextRunWhileRemovingWhatVanished()
      throws Exception {
    KnowledgeLibrary library =
        library(minio.rootCredentials(), List.of(S3Scope.of(bucket, "viele/")));
    S3IndexingExecutor executor = executor();
    UUID firstJob = UUID.randomUUID();

    executor.execute(firstJob, library, IndexingRunMode.FULL);

    assertThat(ingests).hasSize(MANY + 1);
    assertThat(ingests)
        .allSatisfy(
            ingest -> {
              assertThat(ingest.filePath()).startsWith("s3://" + bucket + "/viele/");
              assertThat(ingest.changeMarker()).startsWith("e:").endsWith("|" + ingestSize(ingest));
              assertThat(ingest.sourceType()).isEqualTo(DocumentSourceType.S3);
              assertThat(ingest.context().containerKey()).isEqualTo(bucket);
            });
    assertThat(reconciledPaths(library, executor))
        .hasSize(MANY + 2)
        .contains("s3://" + bucket + "/viele/", "s3://" + bucket + "/viele/sitzung.pdf")
        .doesNotContain("s3://" + bucket + "/andere/nicht-im-bereich.txt");
    verify(indexingJobService).recordListingAssessment(firstJob, true, List.of());
    verify(indexingJobService).completeJob(firstJob, MANY + 1, 0, 1, MANY + 1);

    // second run: the same bestand is stored at its features, one object has meanwhile vanished
    rememberAsStored(List.copyOf(ingests));
    ingests.clear();
    String vanished = "viele/00007.txt";
    minio.deleteObject(bucket, vanished);
    Document vanishedRow =
        new Document(
            "weg.txt",
            "s3://" + bucket + "/viele/weg.txt",
            "text/plain",
            1L,
            DocumentSourceType.S3);
    storedDocuments.add(vanishedRow);
    UUID secondJob = UUID.randomUUID();
    S3IndexingExecutor secondRun = executor();

    secondRun.execute(secondJob, library, IndexingRunMode.FULL);

    assertThat(ingests).as("nothing changed, nothing downloaded").isEmpty();
    verify(indexingJobService).completeJob(secondJob, 0, 0, MANY + 1, 0);
    verify(documentRepository).delete(vanishedRow);
    verify(documentRepository)
        .delete(
            argThat(
                document -> document != null && document.getFilePath().endsWith("/" + vanished)));
    verify(eventRepository)
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REMOVED
                        && event.getReference().endsWith("/" + vanished)));
  }

  private static long ingestSize(DocumentIngest ingest) {
    return ((DocumentIngest.File) ingest.content()).byteSize();
  }

  @Test
  void aScopeTheCredentialsCannotListLeavesTheBestandAloneAndTheRestIsProcessed() throws Exception {
    String own = minio.createBucket("opaa-eigen");
    minio.putObject(own, "satzung.txt", "satzung", "text/plain");
    S3Credentials ownOnly =
        minio.createUser(
            MinioFixture.policyAllowing(
                own, "s3:ListBucket", "s3:GetObject", "s3:GetBucketLocation"));
    KnowledgeLibrary library =
        library(ownOnly, List.of(S3Scope.of(bucket, "viele/"), S3Scope.of(own, "")));
    S3IndexingExecutor executor = executor();
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    assertThat(ingests).hasSize(1);
    assertThat(ingests.get(0).filePath()).isEqualTo("s3://" + own + "/satzung.txt");
    verify(eventRepository)
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && event.getReference().equals(bucket + "/viele/")
                        && event.getMessage().contains(S3FullSync.UNLISTABLE_SCOPE_SUFFIX)));
    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    verify(indexingJobService).recordListingAssessment(jobId, false, List.of(bucket + "/viele/"));
    verify(indexingJobService).completeJob(jobId, 1, 0, 0, 1);
  }
}
