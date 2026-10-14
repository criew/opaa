package io.opaa.indexing.source.dropbox;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.filesync.FileSync;
import io.opaa.indexing.filesync.FileSyncSettings;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.IndexingRunFailedException;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.ScanJournal;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.SourceSyncState;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceType;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;

/**
 * Runs a Dropbox library: a full sync that lists every folder and removes what it did not meet, or
 * a change run that reads each folder's change stream from its cursor. The change run is the
 * default while a full sync completed within the rhythm and every folder has a cursor.
 */
public class DropboxIndexingExecutor implements SourceIndexingExecutor {

  private final DropboxApiFactory apis;
  private final DocumentIngestService documentIngestService;
  private final DocumentRepository documentRepository;
  private final LibraryFolderService folderService;
  private final ScanJournal journal;
  private final Clock clock;
  private final IndexingRunTemplate runTemplate;
  private final SupportedDocumentFormats supportedFormats;

  public DropboxIndexingExecutor(
      DropboxApiFactory apis,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService folderService,
      ScanJournal journal,
      Clock clock,
      IndexingRunTemplate runTemplate,
      SupportedDocumentFormats supportedFormats) {
    this.apis = apis;
    this.documentIngestService = documentIngestService;
    this.documentRepository = documentRepository;
    this.folderService = folderService;
    this.journal = journal;
    this.clock = clock;
    this.runTemplate = runTemplate;
    this.supportedFormats = supportedFormats;
  }

  @Override
  public SourceType sourceType() {
    return DropboxSourceConnector.TYPE;
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
    DropboxSettings dropboxSettings;
    try {
      dropboxSettings = DropboxSettings.stored(settings);
    } catch (RuntimeException e) {
      return IndexingRunMode.FULL;
    }
    if (dropboxSettings == null) {
      return IndexingRunMode.FULL;
    }
    Duration interval = interval(dropboxSettings);
    return journal
        .find(library.getId())
        .filter(
            state ->
                state.canReadChanges(
                    new LinkedHashSet<>(dropboxSettings.folders()), interval, clock.instant()))
        .map(state -> IndexingRunMode.INCREMENTAL)
        .orElse(IndexingRunMode.FULL);
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    runTemplate.run(jobId, targetLibrary, runMode, this, run -> sync(run, runMode));
  }

  ListingOutcome sync(IndexingRun run, IndexingRunMode runMode) throws InterruptedException {
    DropboxSettings settings;
    try {
      settings = DropboxSettings.stored(run.settings().connectorSettings());
    } catch (RuntimeException e) {
      throw new IndexingRunFailedException(e.getMessage());
    }
    if (settings == null) {
      throw new IndexingRunFailedException("Die Bibliothek hat keine Dropbox-Ordner.");
    }
    DropboxProperties properties = apis.properties();
    RequestBudget budget =
        new RequestBudget(new SourceRequestMeter(), properties.requestBudgetPerRun(), null);
    DropboxApi api = apis.open(run.settings(), run.credentials(), budget);
    run.recordRequestCost(budget.meter());
    SourceSyncState state = journal.load(run.library().getId());
    try (DropboxFileStore store = new DropboxFileStore(api, settings, properties.pageSize());
        FileSync sync =
            new FileSync(
                run,
                store,
                new FileSyncSettings(
                    properties.maxFileSizeBytes(),
                    properties.maxFilesPerRun(),
                    properties.downloadConcurrency(),
                    "dropbox-download-"),
                new DropboxWording(),
                documentIngestService,
                documentRepository,
                folderService,
                state,
                journal,
                clock,
                supportedFormats)) {
      return runMode == IndexingRunMode.INCREMENTAL ? sync.runChanges() : sync.run();
    }
  }

  private Duration interval(DropboxSettings settings) {
    return settings.fullSyncIntervalDays() != null
        ? Duration.ofDays(settings.fullSyncIntervalDays())
        : apis.properties().fullSyncInterval();
  }
}
