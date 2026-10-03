package io.opaa.indexing.source.smb;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.common.ValidationException;
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
 * Runs the full sync of an SMB library on {@link FileSync}: every configured folder is walked, a
 * file whose modification time and size are stored is not read again, and a complete listing
 * removes what it did not meet. SMB offers no change log a run could resume from, so every run is a
 * full one.
 */
public class SmbIndexingExecutor implements SourceIndexingExecutor, FileSyncWording {

  private final SmbProperties properties;
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

  public SmbIndexingExecutor(
      SmbProperties properties,
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
    return SmbSourceConnector.TYPE;
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
   * Opens the run's share from the resolved configuration and syncs every folder. A defect in the
   * configuration fails the run with its German sentence before any request.
   */
  ListingOutcome fullSync(IndexingRun run) throws InterruptedException {
    SmbSourceSettings settings;
    SmbAddress address;
    SmbCredentials credentials;
    try {
      settings = SmbSourceSettings.read(run.settings().connectorSettings());
      address = SmbAddress.parse(run.settings().sourceUrl());
      credentials = SmbCredentials.parse(run.currentCredentials());
    } catch (ValidationException | SmbAddress.InvalidSmbConfigurationException e) {
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
    SmbShareClient smb =
        SmbShareClient.of(
            address, credentials, targetAddressValidator, budget, properties.requestTimeout());
    try (SmbFileStore store = new SmbFileStore(smb, address, settings, properties.listPageSize());
        FileSync sync =
            new FileSync(
                run,
                store,
                new FileSyncSettings(
                    properties.maxFileSizeBytes(),
                    properties.maxEntriesPerRun(),
                    properties.downloadConcurrency(),
                    "smb-download-"),
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
        + " Bytes (opaa.indexing.smb.max-file-size-bytes) und wurde übersprungen.";
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
    return "Auf der Freigabe nicht mehr vorhanden, entfernt";
  }

  @Override
  public String droppedReferencesNote() {
    return " gemeldete Dateien wurden verworfen";
  }

  @Override
  public String budgetStallAdvice() {
    return "Der Lauf hat keine Datei neu aufgenommen. Budget anheben"
        + " (opaa.indexing.smb.request-budget-per-run) oder die Ordner aufteilen.";
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
