package io.opaa.indexing.source.nextcloud;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.filesync.FileEntry;
import io.opaa.indexing.filesync.FileSync;
import io.opaa.indexing.filesync.FileSyncSettings;
import io.opaa.indexing.filesync.FileSyncWording;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.IndexingRunFailedException;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.SourceSyncState;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.SourceRequestMeter;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;

/**
 * Runs the full sync of a Nextcloud library on {@link FileSync}: every configured folder is walked
 * with {@code PROPFIND} depth 1, unchanged folders are skipped by their ETag, and a complete
 * listing removes what it did not meet. There is no change log and no push, so every run is a full
 * one.
 */
public class NextcloudIndexingExecutor implements SourceIndexingExecutor, FileSyncWording {

  private final NextcloudProperties properties;
  private final TargetAddressValidator targetAddressValidator;
  private final SourceRequestPolicy requestPolicy;
  private final DocumentIngestService documentIngestService;
  private final DocumentRepository documentRepository;
  private final LibraryFolderService folderService;
  private final StaleDocumentCleanupService cleanupService;
  private final SourceSyncStateRepository syncStateRepository;
  private final Clock clock;
  private final IndexingRunTemplate runTemplate;
  private final SupportedDocumentFormats supportedFormats;

  public NextcloudIndexingExecutor(
      NextcloudProperties properties,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy requestPolicy,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService folderService,
      StaleDocumentCleanupService cleanupService,
      SourceSyncStateRepository syncStateRepository,
      Clock clock,
      IndexingRunTemplate runTemplate,
      SupportedDocumentFormats supportedFormats) {
    this.properties = properties;
    this.targetAddressValidator = targetAddressValidator;
    this.requestPolicy = requestPolicy;
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
  public SourceType sourceType() {
    return NextcloudSourceConnector.TYPE;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    return Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE);
  }

  @Override
  public IndexingRunMode defaultRunMode(KnowledgeLibrary library, ConnectorData settings) {
    return IndexingRunMode.FULL;
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    runTemplate.run(jobId, targetLibrary, runMode, this, this::fullSync);
  }

  /**
   * Opens the run's WebDAV access from the resolved configuration and syncs every folder. A defect
   * in the configuration fails the run with its German sentence before any request.
   */
  ListingOutcome fullSync(IndexingRun run) throws InterruptedException {
    NextcloudSourceSettings settings;
    NextcloudConnection connection;
    try {
      settings = NextcloudSourceSettings.read(run.settings().connectorSettings());
      connection = NextcloudConnection.of(run.settings(), run.credentials().value());
    } catch (io.opaa.common.ValidationException
        | NextcloudConnection.InvalidNextcloudConfigurationException e) {
      throw new IndexingRunFailedException(e.getMessage());
    }
    RequestBudget budget =
        new RequestBudget(
            new SourceRequestMeter(),
            properties.requestBudgetPerRun(),
            requestPolicy.maxRateLimitWaitPerRun());
    run.recordRequestCost(budget.meter());
    UUID libraryId = run.library().getId();
    SourceSyncState state =
        syncStateRepository
            .findByLibraryId(libraryId)
            .orElseGet(() -> new SourceSyncState(libraryId));
    NextcloudDav dav =
        new NextcloudDav(
            connection,
            run.credentials()
                .derived(
                    secret -> NextcloudConnection.of(run.settings(), secret).authorizationHeader()),
            targetAddressValidator,
            requestPolicy,
            budget,
            properties.requestTimeout(),
            properties.maxResponseBytes());
    try (NextcloudFileStore store = new NextcloudFileStore(dav, settings);
        FileSync sync =
            new FileSync(
                run,
                store,
                new FileSyncSettings(
                    properties.maxFileSizeBytes(),
                    properties.maxEntriesPerRun(),
                    properties.downloadConcurrency(),
                    "nextcloud-download-",
                    properties.fullDescentInterval()),
                this,
                documentIngestService,
                documentRepository,
                folderService,
                cleanupService,
                state,
                syncStateRepository,
                clock,
                supportedFormats)) {
      return sync.run();
    }
  }

  @Override
  public String tooLarge(FileEntry entry, long maxBytes) {
    return "Die Datei „"
        + entry.fileName()
        + "“ ist größer als "
        + maxBytes
        + " Bytes (opaa.indexing.nextcloud.max-file-size-bytes) und wurde übersprungen.";
  }

  @Override
  public String tooManyEntries(long maxEntries) {
    return "Die Ordner dieser Bibliothek enthalten mehr als "
        + maxEntries
        + " Dateien; so viele verarbeitet ein Lauf nicht. Bitte die Ordner enger fassen oder die"
        + " Bibliothek aufteilen.";
  }

  @Override
  public String goneConfirmed() {
    return "Von Nextcloud als gelöscht bestätigt, entfernt";
  }

  @Override
  public String droppedReferencesNote() {
    return " gemeldete Dateien wurden verworfen";
  }

  @Override
  public String budgetStallAdvice() {
    return "Der Lauf hat keine Datei neu aufgenommen. Budget anheben"
        + " (opaa.indexing.nextcloud.request-budget-per-run) oder die Ordner aufteilen.";
  }

  @Override
  public String eventRunContinuation() {
    return "der nächste geplante Lauf setzt fort";
  }

  @Override
  public String listedSummary(long listed, long deselected) {
    return listed + " Dateien gelistet, ";
  }

  @Override
  public String checkedSummary(long checked) {
    return checked + " gemeldete Dateien geprüft, ";
  }
}
