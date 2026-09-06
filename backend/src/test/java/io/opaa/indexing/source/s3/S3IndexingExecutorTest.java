package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIngest;
import io.opaa.indexing.DocumentIngests;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunCost;
import io.opaa.indexing.IndexingRunEvent;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.LibraryStorageQuotaService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;

/**
 * The full sync of {@link S3IndexingExecutor} over a {@link FakeS3ObjectStore} (ADR-0027,
 * Entscheidungen 3 bis 5): every scope is listed page by page, the change feature decides before
 * any download, a skipped object stays present, an unlistable scope leaves the bestand alone, and
 * the run frame reconciles only after a complete listing. The frame is real and the reconciliation
 * a spy over the real service, so what the executor hands over is observable.
 */
class S3IndexingExecutorTest {

  private static final String PDF = "application/pdf";
  private static final Instant MODIFIED = Instant.parse("2026-09-01T10:00:00Z");

  private final FakeS3ObjectStore store = new FakeS3ObjectStore();
  private final List<Document> storedDocuments = new ArrayList<>();
  private final List<Path> ingestedFiles = new ArrayList<>();

  private FileProcessingService fileProcessingService;
  private IndexingJobService indexingJobService;
  private IndexingRunEventRepository eventRepository;
  private DocumentRepository documentRepository;
  private StaleDocumentCleanupService cleanupService;
  private LibraryFolderService folderService;
  private S3Properties properties = S3Properties.defaults();
  private KnowledgeLibrary library;
  private S3IndexingExecutor executor;

  @BeforeEach
  void setUp() throws Exception {
    fileProcessingService = mock(FileProcessingService.class);
    when(fileProcessingService.ingest(any(), any()))
        .thenAnswer(
            invocation -> {
              DocumentIngest ingest = invocation.getArgument(0);
              Path file = DocumentIngests.fileOf(ingest);
              assertThat(file).exists();
              ingestedFiles.add(file);
              return FileProcessingResult.PROCESSED;
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
    library = library(settings(List.of(S3Scope.of("dokumente", "2025/"))));
    executor = executorOver(store);
  }

  private S3IndexingExecutor executorOver(FakeS3ObjectStore objectStore) throws S3AccessException {
    S3ClientFactory clientFactory = mock(S3ClientFactory.class);
    when(clientFactory.createForRun(any(), any())).thenReturn(objectStore);
    return new S3IndexingExecutor(
        clientFactory,
        properties,
        fileProcessingService,
        documentRepository,
        folderService,
        new IndexingRunTemplate(
            indexingJobService,
            eventRepository,
            cleanupService,
            documentRepository,
            mock(LibraryStorageQuotaService.class)));
  }

  private static S3SourceSettings settings(List<S3Scope> scopes) {
    return new S3SourceSettings("eu-central-1", true, scopes, null, null);
  }

  private static KnowledgeLibrary library(S3SourceSettings settings) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Protokolle",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.S3,
            null,
            "https://minio.intern.example:9000",
            null,
            "AKIAEXAMPLE:geheim",
            false);
    library.updateS3Settings(settings);
    return library;
  }

  /** A document of the library as a previous run stored it, indexed at {@code marker}. */
  private void stored(String filePath, String marker) {
    Document document =
        new Document(
            filePath.substring(filePath.lastIndexOf('/') + 1),
            filePath,
            PDF,
            10L,
            DocumentSourceType.S3);
    document.setStatus(DocumentStatus.INDEXED);
    document.setLastModifiedRemote(marker);
    storedDocuments.add(document);
  }

  private String markerOf(String bucket, String key) {
    FakeS3ObjectStore.StoredObject object = store.stored(bucket, key).orElseThrow();
    return "e:" + object.eTag() + "|" + object.bytes().length;
  }

  private static ArgumentMatcher<IndexingRunEvent> event(
      IndexingEventCategory category, String messagePart, String reference) {
    return event ->
        event.getCategory() == category
            && event.getMessage().contains(messagePart)
            && (reference == null || reference.equals(event.getReference()));
  }

  private Set<String> reconciledPaths() {
    return reconciliation().getAllValues().get(0);
  }

  /** The reconciliation's present paths (index 0) and reprocessed paths (index 1). */
  @SuppressWarnings("unchecked")
  private ArgumentCaptor<Set<String>> reconciliation() {
    ArgumentCaptor<Set<String>> sets = ArgumentCaptor.forClass(Set.class);
    verify(cleanupService)
        .reconcile(
            eq(library),
            eq(DocumentSourceType.S3),
            sets.capture(),
            sets.capture(),
            any(),
            eq(executor),
            eq(IndexingRunMode.FULL));
    return sets;
  }

  private void verifyNoReconciliation() {
    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void servesS3WithTheFullModeOnly() {
    assertThat(executor.sourceType()).isEqualTo(IndexingSourceType.S3);
    assertThat(executor.runModes())
        .containsExactly(Map.entry(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE));
    assertThat(executor.defaultRunMode(null)).isEqualTo(IndexingRunMode.FULL);
  }

  @Test
  void listsEveryScopePageByPageAndHandsEachAdmittedObjectToTheDocumentPath() throws Exception {
    library =
        library(settings(List.of(S3Scope.of("dokumente", "2025/"), S3Scope.of("satzungen", ""))));
    store
        .pageSize(2)
        .put("dokumente", "2025/q1/protokoll-1.pdf", "eins", PDF)
        .put("dokumente", "2025/q1/protokoll-2.pdf", "zwei", PDF)
        .put("dokumente", "2025/q2/protokoll-3.pdf", "drei", PDF)
        .put("dokumente", "2025/q2/foto.png", "png", "image/png")
        .put("dokumente", "2024/alt.pdf", "alt", PDF)
        .put("satzungen", "hauptsatzung.txt", "text", "text/plain");
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(fileProcessingService)
        .ingest(
            DocumentIngests.that()
                .file()
                .at("s3://dokumente/2025/q1/protokoll-1.pdf")
                .named("protokoll-1.pdf")
                .from(DocumentSourceType.S3)
                .withContext(new SourceDocumentContext("dokumente", "q1"))
                .marked(markerOf("dokumente", "2025/q1/protokoll-1.pdf"))
                .sized(4)
                .in(library)
                .match(),
            any());
    verify(fileProcessingService)
        .ingest(
            DocumentIngests.that()
                .file()
                .at("s3://satzungen/hauptsatzung.txt")
                .withContext(new SourceDocumentContext("satzungen", null))
                .in(library)
                .match(),
            any());
    assertThat(store.calls())
        .as("both scopes listed to the last page, the unsupported image never fetched")
        .containsExactly(
            "list dokumente/2025/",
            "get dokumente/2025/q1/protokoll-1.pdf",
            "get dokumente/2025/q1/protokoll-2.pdf",
            "list dokumente/2025/ @2",
            "get dokumente/2025/q2/protokoll-3.pdf",
            "list satzungen",
            "get satzungen/hauptsatzung.txt");
    assertThat(reconciledPaths())
        .as("every listed object is present, the rejected image included")
        .containsExactlyInAnyOrder(
            "s3://dokumente/2025/q1/protokoll-1.pdf",
            "s3://dokumente/2025/q1/protokoll-2.pdf",
            "s3://dokumente/2025/q2/protokoll-3.pdf",
            "s3://dokumente/2025/q2/foto.png",
            "s3://satzungen/hauptsatzung.txt");
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.UNSUPPORTED_FORMAT,
                    "Dateiformat wird nicht unterstützt",
                    "s3://dokumente/2025/q2/foto.png")));
    verify(indexingJobService).recordListingAssessment(jobId, true, List.of());
    verify(indexingJobService).completeJob(jobId, 4, 0, 1, 4);
    assertThat(ingestedFiles).hasSize(4).allSatisfy(file -> assertThat(file).doesNotExist());
    assertThat(store.isClosed()).isTrue();
    ArgumentCaptor<IndexingRunCost> cost = ArgumentCaptor.forClass(IndexingRunCost.class);
    verify(indexingJobService).recordRunMetrics(eq(jobId), cost.capture());
    assertThat(cost.getValue().requestsSent()).isEqualTo(7);
    assertThat(cost.getValue().incomplete()).isFalse();
  }

  @Test
  void anUnchangedObjectIsSkippedWithoutADownloadAndAChangedOneReprocessed() throws Exception {
    store
        .put("dokumente", "2025/gleich.pdf", "unverändert", PDF)
        .put("dokumente", "2025/anders.pdf", "neu", PDF);
    stored("s3://dokumente/2025/gleich.pdf", markerOf("dokumente", "2025/gleich.pdf"));
    stored("s3://dokumente/2025/anders.pdf", "e:veraltet|3");
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    assertThat(store.calls())
        .containsExactly("list dokumente/2025/", "get dokumente/2025/anders.pdf");
    verify(fileProcessingService)
        .ingest(
            DocumentIngests.that()
                .file()
                .at("s3://dokumente/2025/anders.pdf")
                .marked(markerOf("dokumente", "2025/anders.pdf"))
                .match(),
            any());
    ArgumentCaptor<Set<String>> sets = reconciliation();
    assertThat(sets.getAllValues().get(0))
        .containsExactlyInAnyOrder(
            "s3://dokumente/2025/gleich.pdf", "s3://dokumente/2025/anders.pdf");
    assertThat(sets.getAllValues().get(1))
        .as("only the re-parsed object had its attachment set freshly enumerated (ADR-0022)")
        .containsExactly("s3://dokumente/2025/anders.pdf");
    verify(indexingJobService).completeJob(jobId, 1, 0, 1, 1);
  }

  @Test
  void theChangeFeatureFallsBackToTheTimestampWithoutAnETag() throws Exception {
    Instant modified = Instant.parse("2026-09-06T12:00:00Z");
    FakeS3ObjectStore withoutETags =
        new FakeS3ObjectStore() {
          @Override
          public S3ListPage listObjects(S3Scope scope, String token) throws S3AccessException {
            S3ListPage page = super.listObjects(scope, token);
            return new S3ListPage(
                page.objects().stream()
                    .map(o -> new S3ObjectSummary(o.key(), null, o.size(), modified, null))
                    .toList(),
                page.nextContinuationToken());
          }
        };
    withoutETags.put("dokumente", "2025/ohne-etag.pdf", "inhalt", PDF);

    executorOver(withoutETags).execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    verify(fileProcessingService)
        .ingest(
            DocumentIngests.that()
                .file()
                .at("s3://dokumente/2025/ohne-etag.pdf")
                .marked("t:" + modified.toEpochMilli() + "|6")
                .match(),
            any());
  }

  @Test
  void anUnlistableScopeMakesTheListingIncompleteWhileTheOthersAreStillProcessed()
      throws Exception {
    library =
        library(
            settings(List.of(S3Scope.of("geheim", "intern/"), S3Scope.of("dokumente", "2025/"))));
    store
        .failBucket("geheim", () -> new S3AccessException.ListForbidden("geheim"))
        .put("dokumente", "2025/protokoll.pdf", "inhalt", PDF);
    stored("s3://geheim/intern/vertrag.pdf", "e:alt|1");
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(fileProcessingService)
        .ingest(
            DocumentIngests.that().file().at("s3://dokumente/2025/protokoll.pdf").match(), any());
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED,
                    "Geltungsbereich „geheim/intern/“: Der Bucket „geheim“ darf mit diesen"
                        + " Zugangsdaten nicht aufgelistet werden (s3:ListBucket fehlt)."
                        + S3FullSync.UNLISTABLE_SCOPE_SUFFIX,
                    "geheim/intern/")));
    verifyNoReconciliation();
    verify(indexingJobService).recordListingAssessment(jobId, false, List.of("geheim/intern/"));
    verify(indexingJobService).completeJob(jobId, 1, 0, 0, 1);
  }

  @Test
  void aMissingBucketIsAnUnlistableScopeNotARunFailure() throws Exception {
    store.failBucket("dokumente", () -> new S3AccessException.BucketNotFound("dokumente"));
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED,
                    "Der Bucket „dokumente“ existiert nicht",
                    "dokumente/2025/")));
    verify(indexingJobService).recordListingAssessment(jobId, false, List.of("dokumente/2025/"));
    verify(indexingJobService).completeJob(jobId, 0, 0, 0, 0);
  }

  @Test
  void folderMarkersArchiveClassesAndOversizedObjectsAreNamedSkippedAndStillPresent()
      throws Exception {
    properties = new S3Properties(0, 10, null, null, null, 0, null, 0);
    executor = executorOver(store);
    store
        .put(
            "dokumente",
            "2025/",
            new FakeS3ObjectStore.StoredObject(new byte[0], null, MODIFIED, "STANDARD", false))
        .put(
            "dokumente",
            "2025/eiskalt.pdf",
            new FakeS3ObjectStore.StoredObject(
                "archiv".getBytes(), PDF, MODIFIED, "DEEP_ARCHIVE", true))
        .put("dokumente", "2025/riesig.pdf", "mehr als zehn bytes", PDF)
        .put("dokumente", "2025/klein.pdf", "klein", PDF);
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    assertThat(store.calls())
        .containsExactly("list dokumente/2025/", "get dokumente/2025/klein.pdf");
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.UNSUPPORTED_FORMAT,
                    "1" + S3FullSync.FOLDER_MARKERS_SUFFIX,
                    null)));
    verify(eventRepository, never())
        .save(argThat(event -> "s3://dokumente/2025/".equals(event.getReference())));
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED,
                    "Das Objekt „dokumente/2025/eiskalt.pdf“ liegt in der Archivklasse DEEP_ARCHIVE",
                    "s3://dokumente/2025/eiskalt.pdf")));
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED,
                    "überschreitet die Größenobergrenze von 10 Bytes",
                    "s3://dokumente/2025/riesig.pdf")));
    assertThat(reconciledPaths())
        .containsExactlyInAnyOrder(
            "s3://dokumente/2025/",
            "s3://dokumente/2025/eiskalt.pdf",
            "s3://dokumente/2025/riesig.pdf",
            "s3://dokumente/2025/klein.pdf");
    verify(indexingJobService).completeJob(jobId, 1, 0, 3, 1);
  }

  @Test
  void anExtensionLessKeyCostsOneHeadObjectWhoseContentTypeDecides() throws Exception {
    store
        .put("dokumente", "2025/protokoll", "pdf ohne endung", PDF)
        .put("dokumente", "2025/bild", "png ohne endung", "image/png")
        .put(
            "dokumente",
            "2025/kalt",
            new FakeS3ObjectStore.StoredObject("x".getBytes(), PDF, MODIFIED, "STANDARD", true));
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    assertThat(store.calls())
        .containsExactly(
            "list dokumente/2025/",
            "head dokumente/2025/bild",
            "head dokumente/2025/kalt",
            "head dokumente/2025/protokoll",
            "get dokumente/2025/protokoll");
    verify(fileProcessingService)
        .ingest(
            DocumentIngests.that()
                .file()
                .at("s3://dokumente/2025/protokoll")
                .named("protokoll")
                .match(),
            any());
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.UNSUPPORTED_FORMAT,
                    "Content-Type image/png",
                    "s3://dokumente/2025/bild")));
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED,
                    "Das Objekt „dokumente/2025/kalt“ liegt in der Archivklasse",
                    "s3://dokumente/2025/kalt")));
    assertThat(reconciledPaths())
        .containsExactlyInAnyOrder(
            "s3://dokumente/2025/protokoll",
            "s3://dokumente/2025/bild",
            "s3://dokumente/2025/kalt");
    verify(indexingJobService).completeJob(jobId, 1, 0, 2, 1);
  }

  @Test
  void includeAndExcludePatternsSelectTheKeysAndADeselectedKeyIsNotPresent() throws Exception {
    library =
        library(
            new S3SourceSettings(
                null,
                true,
                List.of(S3Scope.of("dokumente", "")),
                List.of("**/*.pdf", "*.pdf"),
                List.of("**/entwurf-*")));
    store
        .put("dokumente", "2025/protokoll.pdf", "a", PDF)
        .put("dokumente", "2025/entwurf-protokoll.pdf", "b", PDF)
        .put("dokumente", "2025/notiz.txt", "c", "text/plain")
        .put("dokumente", "wurzel.pdf", "d", PDF);
    stored("s3://dokumente/2025/notiz.txt", "e:alt|1");
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    assertThat(store.calls())
        .containsExactly(
            "list dokumente", "get dokumente/2025/protokoll.pdf", "get dokumente/wurzel.pdf");
    assertThat(reconciledPaths())
        .as("a key outside the patterns is no longer part of the bestand")
        .containsExactlyInAnyOrder(
            "s3://dokumente/2025/protokoll.pdf", "s3://dokumente/wurzel.pdf");
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED, "2" + S3FullSync.EXCLUDED_KEYS_SUFFIX, null)));
    verify(indexingJobService).completeJob(jobId, 2, 0, 0, 2);
  }

  @Test
  void anObjectGoneBetweenListingAndDownloadIsAbsentAnUnreadableOneStaysPresent() throws Exception {
    store
        .put("dokumente", "2025/weg.pdf", "weg", PDF)
        .failRead(
            "dokumente",
            "2025/weg.pdf",
            () -> new S3AccessException.ObjectNotFound("dokumente", "2025/weg.pdf"))
        .put("dokumente", "2025/gesperrt.pdf", "gesperrt", PDF)
        .failRead(
            "dokumente",
            "2025/gesperrt.pdf",
            () -> new S3AccessException.ReadForbidden("dokumente", "2025/gesperrt.pdf"))
        .put("dokumente", "2025/ok.pdf", "ok", PDF);
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED,
                    "existiert nicht",
                    "s3://dokumente/2025/weg.pdf")));
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.REJECTED,
                    "nicht gelesen werden (s3:GetObject fehlt)."
                        + S3FullSync.UNREADABLE_OBJECT_SUFFIX,
                    "s3://dokumente/2025/gesperrt.pdf")));
    assertThat(reconciledPaths())
        .containsExactlyInAnyOrder(
            "s3://dokumente/2025/gesperrt.pdf", "s3://dokumente/2025/ok.pdf");
    verify(indexingJobService).recordListingAssessment(jobId, true, List.of());
    verify(indexingJobService).completeJob(jobId, 1, 0, 2, 1);
  }

  @Test
  void aThrottledOrOddlyAnsweredDownloadCountsAsFailedAndTheRunGoesOn() throws Exception {
    store
        .put("dokumente", "2025/langsam.pdf", "a", PDF)
        .failRead("dokumente", "2025/langsam.pdf", () -> new S3AccessException.RateLimited(5))
        .put("dokumente", "2025/seltsam.pdf", "c", PDF)
        .failRead(
            "dokumente",
            "2025/seltsam.pdf",
            () -> new S3AccessException("Der Objektspeicher antwortete mit HTTP 500."))
        .put("dokumente", "2025/ok.pdf", "b", PDF);
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.UNREACHABLE,
                    "drosselt",
                    "s3://dokumente/2025/langsam.pdf")));
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.UNREACHABLE,
                    "HTTP 500",
                    "s3://dokumente/2025/seltsam.pdf")));
    verify(fileProcessingService)
        .ingest(DocumentIngests.that().file().at("s3://dokumente/2025/ok.pdf").match(), any());
    assertThat(reconciledPaths())
        .as("a failed object is neither gone nor readable - it stays present")
        .containsExactlyInAnyOrder(
            "s3://dokumente/2025/langsam.pdf",
            "s3://dokumente/2025/seltsam.pdf",
            "s3://dokumente/2025/ok.pdf");
    verify(indexingJobService).completeJob(jobId, 1, 2, 0, 1);
  }

  @Test
  void aListingThatStaysThrottledFailsTheRunInsteadOfDisablingTheReconciliation() throws Exception {
    store
        .failNextCall(() -> new S3AccessException.RateLimited(5))
        .put("dokumente", "2025/a.pdf", "a", PDF);
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(indexingJobService).failJob(eq(jobId), argThat(message -> message.contains("drosselt")));
    verify(indexingJobService, never()).recordListingAssessment(any(), eq(false), any());
    verifyNoReconciliation();
  }

  @Test
  void aTruncatedListingWithoutATokenAndAScopeFailingMidWayBothLeaveTheListingIncomplete()
      throws Exception {
    library =
        library(settings(List.of(S3Scope.of("kaputt", ""), S3Scope.of("dokumente", "2025/"))));
    FakeS3ObjectStore midway =
        new FakeS3ObjectStore() {
          @Override
          public S3ListPage listObjects(S3Scope scope, String token) throws S3AccessException {
            if ("2".equals(token)) {
              throw new S3AccessException.ListForbidden(scope.bucket());
            }
            return super.listObjects(scope, token);
          }
        };
    midway
        .pageSize(1)
        .failBucket("kaputt", () -> new S3AccessException.ListingIncomplete("kaputt"))
        .put("dokumente", "2025/a.pdf", "a", PDF)
        .put("dokumente", "2025/b.pdf", "b", PDF)
        .put("dokumente", "2025/c.pdf", "c", PDF);
    UUID jobId = UUID.randomUUID();

    executorOver(midway).execute(jobId, library, IndexingRunMode.FULL);

    verify(eventRepository)
        .save(
            argThat(event(IndexingEventCategory.REJECTED, "abgeschnittene Auflistung", "kaputt")));
    verify(eventRepository)
        .save(
            argThat(
                event(IndexingEventCategory.REJECTED, "s3:ListBucket fehlt", "dokumente/2025/")));
    verify(fileProcessingService)
        .ingest(DocumentIngests.that().file().at("s3://dokumente/2025/a.pdf").match(), any());
    verify(fileProcessingService)
        .ingest(DocumentIngests.that().file().at("s3://dokumente/2025/b.pdf").match(), any());
    verifyNoReconciliation();
    verify(indexingJobService)
        .recordListingAssessment(jobId, false, List.of("kaputt", "dokumente/2025/"));
    verify(indexingJobService).completeJob(jobId, 2, 0, 0, 2);
  }

  @Test
  void aSpentBudgetEndsTheRunTruncatedWithoutReconciliation() throws Exception {
    FakeS3ObjectStore budgeted =
        new FakeS3ObjectStore() {
          @Override
          public S3ListPage listObjects(S3Scope scope, String token) throws S3AccessException {
            if (token != null) {
              throw new S3AccessException.BudgetExhausted(3);
            }
            return super.listObjects(scope, token);
          }
        };
    budgeted
        .pageSize(1)
        .put("dokumente", "2025/eins.pdf", "1", PDF)
        .put("dokumente", "2025/zwei.pdf", "2", PDF);
    UUID jobId = UUID.randomUUID();

    executorOver(budgeted).execute(jobId, library, IndexingRunMode.FULL);

    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.BUDGET_EXHAUSTED,
                    "Anfragebudget von 3 Anfragen erschöpft; der Lauf endet unvollständig",
                    null)));
    verify(eventRepository, never())
        .save(argThat(event(IndexingEventCategory.ERROR, "reicht für diese Bibliothek", null)));
    verifyNoReconciliation();
    verify(indexingJobService, never()).recordListingAssessment(any(), eq(false), any());
    verify(indexingJobService, never()).recordListingAssessment(any(), eq(true), any());
    ArgumentCaptor<IndexingRunCost> cost = ArgumentCaptor.forClass(IndexingRunCost.class);
    verify(indexingJobService).recordRunMetrics(eq(jobId), cost.capture());
    assertThat(cost.getValue().incomplete()).isTrue();
    verify(indexingJobService).completeJob(eq(jobId), eq(1), eq(0), eq(0), anyInt());
  }

  @Test
  void aBudgetThatAdmitsNothingNewIsReportedAsAnErrorAndNamesTheUnlistableScopes()
      throws Exception {
    library =
        library(
            settings(List.of(S3Scope.of("geheim", "intern/"), S3Scope.of("dokumente", "2025/"))));
    FakeS3ObjectStore budgeted =
        new FakeS3ObjectStore() {
          @Override
          public S3Download getObject(String bucket, String key, long maxBytes)
              throws S3AccessException {
            throw new S3AccessException.BudgetExhausted(2);
          }
        };
    budgeted
        .failBucket("geheim", () -> new S3AccessException.ListForbidden("geheim"))
        .put("dokumente", "2025/eins.pdf", "1", PDF);

    executorOver(budgeted).execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.BUDGET_EXHAUSTED,
                    "bis dahin nicht auflistbar: geheim/intern/",
                    null)));
    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.ERROR,
                    "Das Anfragebudget von 2 Anfragen reicht für diese Bibliothek nicht aus",
                    null)));
  }

  @Test
  void tooManyListedObjectsFailTheRunVisibly() throws Exception {
    properties = new S3Properties(0, 0, null, null, null, 0, null, 2);
    executor = executorOver(store);
    store
        .put("dokumente", "2025/a.pdf", "a", PDF)
        .put("dokumente", "2025/b.pdf", "b", PDF)
        .put("dokumente", "2025/c.pdf", "c", PDF);
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(indexingJobService)
        .failJob(
            eq(jobId),
            argThat(
                message -> message.contains("mehr als 2 Objekte") && message.contains("enger")));
    verifyNoReconciliation();
  }

  @Test
  void aRefusedKeyFailsTheRunWithTheAccessLayersMessage() throws Exception {
    store
        .failNextCall(() -> new S3AccessException.Authentication("InvalidAccessKeyId"))
        .put("dokumente", "2025/a.pdf", "a", PDF);
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(indexingJobService)
        .failJob(
            eq(jobId),
            argThat(
                message ->
                    message.contains("Zugangsdaten abgelehnt")
                        && message.contains("InvalidAccessKeyId")
                        && !message.contains("AKIAEXAMPLE")
                        && !message.contains("geheim")));
    verifyNoReconciliation();
    assertThat(store.isClosed()).isTrue();
  }

  @Test
  void aStoreThatBecomesUnreachableMidRunFailsTheRun() throws Exception {
    store
        .put("dokumente", "2025/a.pdf", "a", PDF)
        .failRead("dokumente", "2025/a.pdf", () -> new S3AccessException.Unreachable("Timeout"));
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(indexingJobService)
        .failJob(eq(jobId), argThat(message -> message.contains("nicht erreichbar: Timeout")));
    verifyNoReconciliation();
  }

  @Test
  void aLibraryWithoutUsableConfigurationFailsBeforeAnyRequest() {
    KnowledgeLibrary broken =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Kaputt",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.S3,
            null,
            "https://minio.intern.example:9000",
            null,
            "nur-ein-teil",
            false);
    broken.updateS3Settings(settings(List.of(S3Scope.of("dokumente", ""))));
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, broken, IndexingRunMode.FULL);

    verify(indexingJobService)
        .failJob(eq(jobId), argThat(message -> message.contains("Doppelpunkt")));
    assertThat(store.calls()).isEmpty();
  }

  @Test
  void throttlingIsSummarisedAndTheRequestCostRecorded() throws Exception {
    store.put("dokumente", "2025/a.pdf", "a", PDF);
    store.meter().recordThrottle();
    store.meter().recordThrottleWait(Duration.ofSeconds(2));
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(eventRepository)
        .save(argThat(event(IndexingEventCategory.RATE_LIMITED, "1-mal gedrosselt", null)));
    ArgumentCaptor<IndexingRunCost> cost = ArgumentCaptor.forClass(IndexingRunCost.class);
    verify(indexingJobService).recordRunMetrics(eq(jobId), cost.capture());
    assertThat(cost.getValue().throttleCount()).isEqualTo(1);
    assertThat(cost.getValue().throttleWaitMillis()).isEqualTo(2000);
  }

  @Test
  void aSameChecksumAfterANewETagIsSkippedNotFailedAndStaysPresent() throws Exception {
    doReturn(FileProcessingResult.SKIPPED).when(fileProcessingService).ingest(any(), any());
    store.put("dokumente", "2025/kopie.pdf", "gleicher inhalt", PDF);
    stored("s3://dokumente/2025/kopie.pdf", "e:vorher|15");
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    assertThat(store.calls()).contains("get dokumente/2025/kopie.pdf");
    verify(indexingJobService).completeJob(jobId, 0, 0, 1, 0);
    assertThat(reconciledPaths()).containsExactly("s3://dokumente/2025/kopie.pdf");
  }

  @Test
  void aFailingIngestIsCountedAsFailedAndTheTempFileStillDeleted() throws Exception {
    doAnswer(
            invocation -> {
              ingestedFiles.add(DocumentIngests.fileOf(invocation.getArgument(0)));
              throw new IOException("Platte voll");
            })
        .when(fileProcessingService)
        .ingest(any(), any());
    store.put("dokumente", "2025/a.pdf", "a", PDF);
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(eventRepository)
        .save(
            argThat(
                event(
                    IndexingEventCategory.ERROR,
                    "Verarbeitung fehlgeschlagen",
                    "s3://dokumente/2025/a.pdf")));
    verify(indexingJobService).completeJob(jobId, 0, 1, 0, 0);
    assertThat(ingestedFiles).hasSize(1).allSatisfy(file -> assertThat(file).doesNotExist());
    assertThat(reconciledPaths()).containsExactly("s3://dokumente/2025/a.pdf");
  }

  @Test
  void mirrorsTheKeysFoldersAndOpensEveryChainWithBucketAndPrefixForSeveralScopes()
      throws Exception {
    library =
        library(settings(List.of(S3Scope.of("dokumente", "2025/"), S3Scope.of("satzungen", ""))));
    UUID q1 = UUID.randomUUID();
    UUID satzungen = UUID.randomUUID();
    when(folderService.materializeFolderPath(library, List.of("dokumente", "2025", "q1")))
        .thenReturn(q1);
    when(folderService.materializeFolderPath(library, List.of("satzungen"))).thenReturn(satzungen);
    store
        .put("dokumente", "2025/q1/neu.pdf", "neu", PDF)
        .put("dokumente", "2025/q1/alt.pdf", "alt", PDF)
        .put("satzungen", "haupt.txt", "haupt", "text/plain");
    stored("s3://dokumente/2025/q1/alt.pdf", markerOf("dokumente", "2025/q1/alt.pdf"));
    Document unchanged = storedDocuments.get(0);

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    verify(fileProcessingService)
        .ingest(
            DocumentIngests.that().file().at("s3://dokumente/2025/q1/neu.pdf").inFolder(q1).match(),
            any());
    verify(fileProcessingService)
        .ingest(
            DocumentIngests.that()
                .file()
                .at("s3://satzungen/haupt.txt")
                .inFolder(satzungen)
                .match(),
            any());
    assertThat(unchanged.getFolderId())
        .as("a row that was not downloaded still receives its place in the structure")
        .isEqualTo(q1);
    verify(documentRepository).save(unchanged);
    verify(folderService).pruneOrphanedFolders(library, Set.of(q1, satzungen));
  }

  @Test
  void aSingleScopeMakesItsPrefixTheRootAndAnIncompleteListingPrunesNothing() throws Exception {
    library =
        library(
            settings(List.of(S3Scope.of("dokumente", "2025/"), S3Scope.of("geheim", "intern/"))));
    store
        .failBucket("geheim", () -> new S3AccessException.ListForbidden("geheim"))
        .put("dokumente", "2025/q1/a.pdf", "a", PDF);

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    verify(folderService).materializeFolderPath(library, List.of("dokumente", "2025", "q1"));
    verify(folderService, never()).pruneOrphanedFolders(any(), any());

    library = library(settings(List.of(S3Scope.of("dokumente", "2025/"))));
    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    verify(folderService).materializeFolderPath(library, List.of("q1"));
    verify(folderService).pruneOrphanedFolders(eq(library), any());
  }

  @Test
  void theHierarchyPathIsTheKeysFolderChainBelowTheScopePrefix() {
    assertThat(S3FullSync.hierarchyPath(S3Scope.of("bucket", "2025/"), "2025/q1/x/a.pdf"))
        .isEqualTo("q1 / x");
    assertThat(S3FullSync.hierarchyPath(S3Scope.of("bucket", "2025/"), "2025/a.pdf")).isNull();
    assertThat(S3FullSync.hierarchyPath(S3Scope.of("bucket", ""), "2025//a.pdf")).isEqualTo("2025");
  }
}
