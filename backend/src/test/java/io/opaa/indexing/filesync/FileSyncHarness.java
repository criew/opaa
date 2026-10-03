package io.opaa.indexing.filesync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.asset.AssetRepository;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.LibrarySourceConnectionResolver;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.SourceSyncState;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.knowledge.SourceType;
import io.opaa.test.ProductionDocumentFormats;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Runs {@link FileSync} over a {@link FileStore} inside the real run frame, with the document
 * bestand, the protocol and the resumption state held in memory across runs: what a connector's
 * store must satisfy is observable without a database.
 */
public final class FileSyncHarness {

  public static final SourceType TYPE = SourceType.of("FILE_SYNC_TEST");

  /** The size bound of every run. */
  public static final long MAX_FILE_SIZE = 1024;

  /** One finished run. */
  public record Run(
      List<IndexingRunEvent> events,
      List<String> ingested,
      Boolean listingComplete,
      List<String> unlistedContainerKeys,
      int processed,
      int failed,
      int skipped,
      String failure) {

    public List<IndexingRunEvent> eventsOf(io.opaa.indexing.job.IndexingEventCategory category) {
      return events.stream().filter(event -> event.getCategory() == category).toList();
    }
  }

  private final List<Document> stored = new ArrayList<>();
  private final List<IndexingRunEvent> events = new ArrayList<>();
  private final List<String> ingested = new ArrayList<>();
  private final Set<String> failingIngests = new HashSet<>();
  private Duration subtreeMemoryMaxAge;
  private Instant now = Instant.parse("2026-10-03T12:00:00Z");
  private final IndexingJobService jobService = mock(IndexingJobService.class);
  private final IndexingRunEventRepository eventRepository = mock(IndexingRunEventRepository.class);
  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final DocumentIngestService ingestService = mock(DocumentIngestService.class);
  private final LibraryFolderService folderService = mock(LibraryFolderService.class);
  private final SourceSyncStateRepository syncStateRepository =
      mock(SourceSyncStateRepository.class);
  private final StaleDocumentCleanupService cleanupService;
  private final IndexingRunTemplate template;
  private final KnowledgeLibrary library;
  private final SourceIndexingExecutor executor;
  private SourceSyncState state;

  private Boolean listingComplete;
  private List<String> unlisted = List.of();
  private int[] counters = new int[3];
  private String failure;

  public FileSyncHarness() throws Exception {
    library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Dateiablage",
            null,
            UUID.randomUUID(),
            TYPE,
            null,
            "https://ablage.example",
            null,
            null,
            false);
    state = new SourceSyncState(library.getId());
    when(documentRepository.findByLibraryIdAndFilePath(any(), any()))
        .thenAnswer(
            invocation ->
                stored.stream()
                    .filter(d -> d.getFilePath().equals(invocation.getArgument(1)))
                    .findFirst());
    when(documentRepository.findByLibraryIdAndSourceType(any(), any()))
        .thenAnswer(invocation -> List.copyOf(stored));
    when(documentRepository.findByLibraryIdAndSourceContainerKey(any(), any()))
        .thenAnswer(
            invocation ->
                stored.stream()
                    .filter(d -> invocation.getArgument(1).equals(d.getSourceContainerKey()))
                    .toList());
    when(documentRepository.countByLibraryIdAndSourceContainerKey(any(), any()))
        .thenAnswer(
            invocation ->
                stored.stream()
                    .filter(d -> invocation.getArgument(1).equals(d.getSourceContainerKey()))
                    .count());
    when(documentRepository.findHierarchyPathsAwaitingAVisit(any(), any()))
        .thenAnswer(
            invocation ->
                stored.stream()
                    .filter(d -> invocation.getArgument(1).equals(d.getSourceContainerKey()))
                    .filter(
                        d ->
                            d.getLastModifiedRemote() == null
                                || d.getStatus() != DocumentStatus.INDEXED)
                    .map(Document::getSourceHierarchyPath)
                    .distinct()
                    .toList());
    when(documentRepository.findInHierarchy(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              String path = invocation.getArgument(2);
              return stored.stream()
                  .filter(d -> invocation.getArgument(1).equals(d.getSourceContainerKey()))
                  .filter(
                      d ->
                          d.getSourceHierarchyPath() != null
                              && FileSync.covers(path, d.getSourceHierarchyPath()))
                  .toList();
            });
    doAnswer(invocation -> stored.remove((Document) invocation.getArgument(0)))
        .when(documentRepository)
        .delete(any(Document.class));
    when(ingestService.ingest(any(), any()))
        .thenAnswer(
            invocation -> {
              DocumentIngest ingest = invocation.getArgument(0);
              if (failingIngests.contains(ingest.filePath())) {
                throw new IllegalStateException("embedding service unavailable");
              }
              ingested.add(ingest.filePath());
              Document document =
                  stored.stream()
                      .filter(d -> d.getFilePath().equals(ingest.filePath()))
                      .findFirst()
                      .orElseGet(
                          () -> {
                            Document created =
                                new Document(
                                    ingest.fileName(), ingest.filePath(), "text/plain", 1L, TYPE);
                            stored.add(created);
                            return created;
                          });
              document.setStatus(DocumentStatus.INDEXED);
              document.setLastModifiedRemote(ingest.changeMarker());
              document.setFileName(ingest.fileName());
              document.applySourceContext(ingest.context());
              return DocumentIngestResult.PROCESSED;
            });
    when(eventRepository.save(any()))
        .thenAnswer(
            invocation -> {
              events.add(invocation.getArgument(0));
              return invocation.getArgument(0);
            });
    doAnswer(
            invocation -> {
              listingComplete = invocation.getArgument(1);
              unlisted = invocation.getArgument(2);
              return null;
            })
        .when(jobService)
        .recordListingAssessment(any(), anyBoolean(), any());
    doAnswer(
            invocation -> {
              counters =
                  new int[] {
                    invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3)
                  };
              return true;
            })
        .when(jobService)
        .completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
    doAnswer(
            invocation -> {
              failure = invocation.getArgument(1);
              return null;
            })
        .when(jobService)
        .failJob(any(), anyString());
    when(syncStateRepository.save(any()))
        .thenAnswer(
            invocation -> {
              state = invocation.getArgument(0);
              return state;
            });
    cleanupService =
        spy(
            new StaleDocumentCleanupService(
                documentRepository, mock(VectorChunkStore.class), mock(AssetRepository.class)));
    template =
        new IndexingRunTemplate(
            jobService,
            eventRepository,
            cleanupService,
            documentRepository,
            mock(LibraryStorageQuotaService.class),
            new LibrarySourceConnectionResolver());
    executor =
        new SourceIndexingExecutor() {
          @Override
          public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
            return Map.of(
                IndexingRunMode.FULL,
                VanishedDocumentPolicy.REMOVE_ON_ABSENCE,
                IndexingRunMode.EVENT,
                VanishedDocumentPolicy.KEEP_ON_ABSENCE);
          }

          @Override
          public IndexingRunMode defaultRunMode(KnowledgeLibrary library, ConnectorData settings) {
            return IndexingRunMode.FULL;
          }

          @Override
          public SourceType sourceType() {
            return TYPE;
          }

          @Override
          public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode mode) {}
        };
  }

  /** One full sync over {@code store}, which is closed afterwards. */
  public Run fullSync(FileStore store) {
    return run(IndexingRunMode.FULL, store, FileSync::run);
  }

  /** One event run over {@code references}, all inside the store's containers. */
  public Run refresh(FileStore store, List<FileReference> references) {
    return run(IndexingRunMode.EVENT, store, sync -> sync.refresh(references, 0, 0));
  }

  /** The bestand after the last run, as {@code file_path}s. */
  public List<String> storedPaths() {
    return stored.stream().map(Document::getFilePath).toList();
  }

  public Optional<Document> stored(String filePath) {
    return stored.stream().filter(d -> d.getFilePath().equals(filePath)).findFirst();
  }

  /** Adds a row as an earlier run stored it. */
  public Document store(String filePath, String changeMarker) {
    Document document =
        new Document(
            filePath.substring(filePath.lastIndexOf('/') + 1), filePath, "text/plain", 1L, TYPE);
    document.setStatus(DocumentStatus.INDEXED);
    document.setLastModifiedRemote(changeMarker);
    stored.add(document);
    return document;
  }

  public SourceSyncState state() {
    return state;
  }

  /** Marks the stored {@code filePath} for its next run, as a reprocessing request does. */
  public FileSyncHarness markForReindex(String filePath) {
    Document document = stored(filePath).orElseThrow();
    document.setChecksum(null);
    document.setLastModifiedRemote(null);
    return this;
  }

  /** Removes the stored {@code filePath} outside a run, as a manual deletion does. */
  public FileSyncHarness deleteStored(String filePath) {
    stored.remove(stored(filePath).orElseThrow());
    return this;
  }

  /** From now on the ingest of {@code filePath} throws, as a failing embedding call would. */
  public FileSyncHarness failIngestOf(String filePath) {
    failingIngests.add(filePath);
    return this;
  }

  public FileSyncHarness healIngests() {
    failingIngests.clear();
    return this;
  }

  public FileSyncHarness subtreeMemoryMaxAge(Duration maxAge) {
    subtreeMemoryMaxAge = maxAge;
    return this;
  }

  /** Moves the runs' clock forward by {@code duration}. */
  public FileSyncHarness advanceClock(Duration duration) {
    now = now.plus(duration);
    return this;
  }

  public LibraryFolderService folderService() {
    return folderService;
  }

  public KnowledgeLibrary library() {
    return library;
  }

  @FunctionalInterface
  private interface Body {
    io.opaa.indexing.source.ListingOutcome run(FileSync sync) throws InterruptedException;
  }

  private Run run(IndexingRunMode mode, FileStore store, Body body) {
    events.clear();
    ingested.clear();
    listingComplete = null;
    unlisted = List.of();
    counters = new int[3];
    failure = null;
    when(syncStateRepository.findByLibraryId(library.getId())).thenReturn(Optional.of(state));
    template.run(
        UUID.randomUUID(),
        library,
        mode,
        executor,
        frame -> {
          frame.recordRequestCost(store.meter());
          try (store;
              FileSync sync =
                  new FileSync(
                      frame,
                      store,
                      new FileSyncSettings(
                          MAX_FILE_SIZE, 1_000, 1, "test-download-", subtreeMemoryMaxAge),
                      WORDING,
                      ingestService,
                      documentRepository,
                      folderService,
                      cleanupService,
                      state,
                      syncStateRepository,
                      Clock.fixed(now, ZoneOffset.UTC),
                      ProductionDocumentFormats.supportedFormats())) {
            return body.run(sync);
          }
        });
    return new Run(
        List.copyOf(events),
        List.copyOf(ingested),
        listingComplete,
        unlisted,
        counters[0],
        counters[1],
        counters[2],
        failure);
  }

  private static final FileSyncWording WORDING =
      new FileSyncWording() {
        @Override
        public String tooLarge(FileEntry entry, long maxBytes) {
          return "„" + entry.filePath() + "“ ist größer als " + maxBytes + " Bytes.";
        }

        @Override
        public String tooManyEntries(long maxEntries) {
          return "Mehr als " + maxEntries + " Einträge.";
        }

        @Override
        public String goneConfirmed() {
          return "Als gelöscht bestätigt, entfernt";
        }

        @Override
        public String droppedReferencesNote() {
          return " gemeldete Einträge verworfen";
        }

        @Override
        public String budgetStallAdvice() {
          return "Kein Eintrag neu aufgenommen.";
        }

        @Override
        public String eventRunContinuation() {
          return "der nächste geplante Lauf setzt fort";
        }

        @Override
        public String listedSummary(long listed, long deselected) {
          return listed + " Einträge gelistet, " + deselected + " abgewählt, ";
        }

        @Override
        public String checkedSummary(long checked) {
          return checked + " gemeldete Einträge geprüft, ";
        }
      };
}
