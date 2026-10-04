package io.opaa.indexing.source.googledrive;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.filesync.FileSync;
import io.opaa.indexing.filesync.FileSyncSettings;
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
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Async;

/**
 * Runs a Google Drive library (ADR-0040, Entscheidung 6): a full sync that lists every scope and
 * removes what it did not meet, or a change run that reads the change streams from their cursors.
 * The change run is the default while a full sync completed within the rhythm and every stream has
 * a cursor.
 */
public class GoogleDriveIndexingExecutor implements SourceIndexingExecutor {

  private final DriveApiFactory apis;
  private final DocumentIngestService documentIngestService;
  private final DocumentRepository documentRepository;
  private final LibraryFolderService folderService;
  private final StaleDocumentCleanupService cleanupService;
  private final SourceSyncStateRepository syncStateRepository;
  private final Clock clock;
  private final IndexingRunTemplate runTemplate;
  private final SupportedDocumentFormats supportedFormats;

  public GoogleDriveIndexingExecutor(
      DriveApiFactory apis,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService folderService,
      StaleDocumentCleanupService cleanupService,
      SourceSyncStateRepository syncStateRepository,
      Clock clock,
      IndexingRunTemplate runTemplate,
      SupportedDocumentFormats supportedFormats) {
    this.apis = apis;
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
    return GoogleDriveSourceConnector.TYPE;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    return Map.of(
        IndexingRunMode.FULL,
        VanishedDocumentPolicy.REMOVE_ON_ABSENCE,
        IndexingRunMode.INCREMENTAL,
        VanishedDocumentPolicy.KEEP_ON_ABSENCE);
  }

  @Override
  public IndexingRunMode defaultRunMode(KnowledgeLibrary library, ConnectorData settings) {
    GoogleDriveSettings driveSettings;
    try {
      driveSettings = GoogleDriveSettings.stored(settings);
    } catch (RuntimeException e) {
      return IndexingRunMode.FULL;
    }
    if (driveSettings == null) {
      return IndexingRunMode.FULL;
    }
    Set<String> streams = streams(driveSettings);
    Duration interval = interval(driveSettings);
    return syncStateRepository
        .findByLibraryId(library.getId())
        .filter(state -> state.canReadChanges(streams, interval, clock.instant()))
        .map(state -> IndexingRunMode.INCREMENTAL)
        .orElse(IndexingRunMode.FULL);
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    runTemplate.run(jobId, targetLibrary, runMode, this, run -> sync(run, runMode));
  }

  ListingOutcome sync(IndexingRun run, IndexingRunMode runMode) throws InterruptedException {
    GoogleDriveSettings settings;
    try {
      settings = GoogleDriveSettings.stored(run.settings().connectorSettings());
    } catch (RuntimeException e) {
      throw new IndexingRunFailedException(e.getMessage());
    }
    if (settings == null) {
      throw new IndexingRunFailedException("Die Bibliothek hat keine Google-Drive-Bereiche.");
    }
    GoogleDriveProperties properties = apis.properties();
    RequestBudget budget =
        new RequestBudget(new SourceRequestMeter(), properties.requestBudgetPerRun(), null);
    DriveApi api;
    try {
      api = apis.open(run.settings(), run.credentials()::value, budget);
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new IndexingRunFailedException(e.getMessage());
    }
    run.recordRequestCost(budget.meter());
    UUID libraryId = run.library().getId();
    SourceSyncState state =
        syncStateRepository
            .findByLibraryId(libraryId)
            .orElseGet(() -> new SourceSyncState(libraryId));
    try (DriveFileStore store = new DriveFileStore(api, settings, properties.pageSize());
        FileSync sync =
            new FileSync(
                run,
                store,
                new FileSyncSettings(
                    properties.maxFileSizeBytes(),
                    properties.maxFilesPerRun(),
                    properties.downloadConcurrency(),
                    "gdrive-download-"),
                new GoogleDriveWording(),
                documentIngestService,
                documentRepository,
                folderService,
                cleanupService,
                state,
                syncStateRepository,
                clock,
                supportedFormats)) {
      return runMode == IndexingRunMode.INCREMENTAL ? sync.runChanges() : sync.run();
    }
  }

  private Duration interval(GoogleDriveSettings settings) {
    return settings.fullSyncIntervalDays() != null
        ? Duration.ofDays(settings.fullSyncIntervalDays())
        : apis.properties().fullSyncInterval();
  }

  static Set<String> streams(GoogleDriveSettings settings) {
    return settings.scopes().stream()
        .map(
            scope ->
                scope.kind() == GoogleDriveScope.Kind.DRIVE
                    ? scope.key()
                    : DriveFileStore.USER_STREAM)
        .collect(Collectors.toSet());
  }
}
