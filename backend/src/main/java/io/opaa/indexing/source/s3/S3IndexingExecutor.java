package io.opaa.indexing.source.s3;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.indexing.format.SupportedDocumentFormats;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.IndexingRunFailedException;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.SourceSyncState;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryFolderService;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link IndexingSourceType#S3} (ADR-0027): exactly one mode, the full
 * sync ({@link S3FullSync}) that lists every scope completely, fetches what its change feature says
 * has changed, and reports a complete listing so the run frame removes what it did not meet. The
 * key prefixes below each scope are mirrored as read-only folders (ADR-0020, ADR-0027 Entscheidung
 * 5) and pruned after the reconciliation. The run's store is bounded by the request budget and
 * closed with the run; its request meter is handed to the frame, which reports throttling and cost
 * whether the sync succeeded or not.
 */
public class S3IndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(S3IndexingExecutor.class);

  private final S3ClientFactory clientFactory;
  private final S3Properties properties;
  private final DocumentIngestService documentIngestService;
  private final DocumentRepository documentRepository;
  private final LibraryFolderService folderService;
  private final StaleDocumentCleanupService cleanupService;
  private final SourceSyncStateRepository syncStateRepository;
  private final Clock clock;
  private final IndexingRunTemplate runTemplate;
  private final SupportedDocumentFormats supportedFormats;

  public S3IndexingExecutor(
      S3ClientFactory clientFactory,
      S3Properties properties,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService folderService,
      StaleDocumentCleanupService cleanupService,
      SourceSyncStateRepository syncStateRepository,
      Clock clock,
      IndexingRunTemplate runTemplate,
      SupportedDocumentFormats supportedFormats) {
    this.clientFactory = clientFactory;
    this.properties = properties;
    this.documentIngestService = documentIngestService;
    this.documentRepository = documentRepository;
    this.folderService = folderService;
    this.cleanupService = cleanupService;
    this.syncStateRepository = syncStateRepository;
    this.clock = clock;
    this.runTemplate = runTemplate;
    this.supportedFormats = supportedFormats;
  }

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.S3;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    // ADR-0027, Entscheidung 3: no incremental mode - the full listing is cheap enough to be the
    // regular one; the event run checks named keys only and never removes by absence.
    return Map.of(
        IndexingRunMode.FULL,
        VanishedDocumentPolicy.REMOVE_ON_ABSENCE,
        IndexingRunMode.EVENT,
        VanishedDocumentPolicy.KEEP_ON_ABSENCE);
  }

  /** Always the full sync: the event run is started by a notification alone, never by default. */
  @Override
  public IndexingRunMode defaultRunMode(KnowledgeLibrary library) {
    return IndexingRunMode.FULL;
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    if (runMode == IndexingRunMode.EVENT) {
      // an event run without reported keys has nothing to check - the frame ends it cleanly
      runTemplate.run(
          jobId,
          targetLibrary,
          runMode,
          this,
          run -> {
            run.events()
                .recordRunNote(
                    IndexingEventCategory.SUMMARY,
                    "Ereignislauf ohne gemeldete Objekte - nichts zu prüfen");
            return ListingOutcome.partial();
          });
      return;
    }
    runTemplate.run(jobId, targetLibrary, runMode, this, this::indexScopes);
  }

  /**
   * The event run (ADR-0027, Entscheidung 6): checks exactly {@code references} ({@code
   * bucket/key}) with one {@code HeadObject} each - a changed object goes the full sync's way, a
   * {@code 404} removes the document with its attachments, anything else changes nothing. Never a
   * listing, never a reconciliation, and the resumption state stays untouched. {@code dropped}
   * events outside the scopes are noted once.
   */
  @Async("indexingTaskExecutor")
  public void refreshObjects(
      UUID jobId, KnowledgeLibrary targetLibrary, Set<String> references, int dropped) {
    runTemplate.run(
        jobId,
        targetLibrary,
        IndexingRunMode.EVENT,
        this,
        run -> withStore(run, sync -> sync.refresh(references, dropped)));
  }

  /** One run body over the open store and sync. */
  @FunctionalInterface
  private interface SyncBody {
    ListingOutcome run(S3FullSync sync) throws InterruptedException;
  }

  /**
   * Opens the run's store from the library's stored configuration and runs the full sync over it. A
   * defect in the configuration or a store the access layer cannot open fails the run with the
   * layer's own German sentence, before any object is touched.
   */
  ListingOutcome indexScopes(IndexingRun run) throws InterruptedException {
    return withStore(run, sync -> sync.run(run.library().getS3Settings().scopes()));
  }

  private ListingOutcome withStore(IndexingRun run, SyncBody body) throws InterruptedException {
    KnowledgeLibrary library = run.library();
    S3SourceSettings settings = library.getS3Settings();
    S3Connection connection;
    try {
      connection = S3LibraryConnection.of(library, settings);
    } catch (S3LibraryConnection.InvalidS3ConfigurationException e) {
      throw new IndexingRunFailedException(e.getMessage());
    }
    S3ObjectStore store;
    try {
      store = clientFactory.createForRun(connection, settings.scopes());
    } catch (S3AccessException e) {
      throw accessFailure(run, e);
    }
    run.recordRequestCost(store.meter());
    UUID libraryId = library.getId();
    SourceSyncState state =
        syncStateRepository
            .findByLibraryId(libraryId)
            .orElseGet(() -> new SourceSyncState(libraryId));
    try (store;
        S3FullSync sync =
            new S3FullSync(
                run,
                store,
                settings,
                properties,
                documentIngestService,
                documentRepository,
                folderService,
                cleanupService,
                state,
                syncStateRepository,
                clock,
                supportedFormats)) {
      return body.run(sync);
    }
  }

  private static IndexingRunFailedException accessFailure(IndexingRun run, S3AccessException e) {
    log.warn("S3 run for library {} failed: {}", run.library().getId(), e.getMessage());
    return new IndexingRunFailedException(e.getMessage(), e);
  }
}
