package io.opaa.indexing.source.sharepoint;

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
import io.opaa.indexing.source.ScanJournal;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.SourceSyncState;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceType;
import io.opaa.msgraph.GraphClient;
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
 * Runs a SharePoint library (ADR-0040, Nachtrag „SharePoint“): a full sync that lists every
 * document library, possibly over several runs, and removes what neither it nor the change log saw;
 * or a change run that reads each library's delta from its cursor. The change run is the default
 * while a full sync completed within the rhythm and every library has a cursor.
 */
public class SharePointIndexingExecutor implements SourceIndexingExecutor {

  private final GraphConnections graphs;
  private final DocumentIngestService documentIngestService;
  private final DocumentRepository documentRepository;
  private final LibraryFolderService folderService;
  private final StaleDocumentCleanupService cleanupService;
  private final ScanJournal journal;
  private final Clock clock;
  private final IndexingRunTemplate runTemplate;
  private final SupportedDocumentFormats supportedFormats;

  SharePointIndexingExecutor(
      GraphConnections graphs,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService folderService,
      StaleDocumentCleanupService cleanupService,
      ScanJournal journal,
      Clock clock,
      IndexingRunTemplate runTemplate,
      SupportedDocumentFormats supportedFormats) {
    this.graphs = graphs;
    this.documentIngestService = documentIngestService;
    this.documentRepository = documentRepository;
    this.folderService = folderService;
    this.cleanupService = cleanupService;
    this.journal = journal;
    this.clock = clock;
    this.runTemplate = runTemplate;
    this.supportedFormats = supportedFormats;
  }

  @Override
  public SourceType sourceType() {
    return SharePointSourceConnector.TYPE;
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
    SharePointSettings sharePointSettings;
    try {
      sharePointSettings = SharePointSettings.stored(settings);
    } catch (RuntimeException e) {
      return IndexingRunMode.FULL;
    }
    if (sharePointSettings == null) {
      return IndexingRunMode.FULL;
    }
    Set<String> streams = streams(sharePointSettings);
    Duration interval = interval(sharePointSettings);
    return journal
        .find(library.getId())
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
    SharePointSettings settings;
    try {
      settings = SharePointSettings.stored(run.settings().connectorSettings());
    } catch (RuntimeException e) {
      throw new IndexingRunFailedException(e.getMessage());
    }
    if (settings == null) {
      throw new IndexingRunFailedException("Die Bibliothek hat keine SharePoint-Bibliotheken.");
    }
    SharePointProperties properties = graphs.properties();
    RequestBudget budget =
        new RequestBudget(
            new SourceRequestMeter(),
            properties.requestBudgetPerRun(),
            properties.maxThrottleWaitPerRun());
    GraphClient graph;
    try {
      graph = graphs.open(run.settings(), run.credentials(), budget);
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new IndexingRunFailedException(e.getMessage());
    }
    run.recordRequestCost(budget.meter());
    UUID libraryId = run.library().getId();
    SourceSyncState state = journal.load(libraryId);
    try (SharePointFileStore store =
            new SharePointFileStore(graph, budget.meter(), settings, properties.pageSize());
        FileSync sync =
            new FileSync(
                run,
                store,
                new FileSyncSettings(
                    properties.maxFileSizeBytes(),
                    properties.maxFilesPerRun(),
                    properties.downloadConcurrency(),
                    "sharepoint-download-"),
                new SharePointWording(),
                documentIngestService,
                documentRepository,
                folderService,
                cleanupService,
                state,
                journal,
                clock,
                supportedFormats)) {
      return runMode == IndexingRunMode.INCREMENTAL ? sync.runChanges() : sync.run();
    }
  }

  private Duration interval(SharePointSettings settings) {
    return settings.fullSyncIntervalDays() != null
        ? Duration.ofDays(settings.fullSyncIntervalDays())
        : graphs.properties().fullSyncInterval();
  }

  /** One change stream per document library, keyed like its container. */
  static Set<String> streams(SharePointSettings settings) {
    return settings.libraries().stream().map(SharePointLibrary::key).collect(Collectors.toSet());
  }
}
