package io.opaa.indexing.source.sharepoint;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.run.IndexingRun;
import io.opaa.indexing.run.IndexingRunTemplate;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.IndexingRunFailedException;
import io.opaa.indexing.spi.ListingOutcome;
import io.opaa.indexing.spi.ScanJournal;
import io.opaa.indexing.spi.SourceIndexingExecutor;
import io.opaa.indexing.spi.VanishedDocumentPolicy;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceType;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Async;

/**
 * Runs a SharePoint library (ADR-0047): a full sync that lists every document library, possibly
 * over several runs, and removes what neither it nor the change log saw; or a change run that reads
 * each library's delta from its cursor. The change run is the default while a full sync completed
 * within the rhythm and every library has a cursor.
 */
public class SharePointIndexingExecutor implements SourceIndexingExecutor {

  private final GraphConnections graphs;
  private final ScanJournal journal;
  private final Clock clock;
  private final IndexingRunTemplate runTemplate;
  private final DriveRun driveRun;

  SharePointIndexingExecutor(
      GraphConnections graphs,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService folderService,
      ScanJournal journal,
      Clock clock,
      IndexingRunTemplate runTemplate,
      SupportedDocumentFormats supportedFormats) {
    this.graphs = graphs;
    this.journal = journal;
    this.clock = clock;
    this.runTemplate = runTemplate;
    this.driveRun =
        new DriveRun(
            graphs,
            documentIngestService,
            documentRepository,
            folderService,
            journal,
            clock,
            supportedFormats);
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
    return driveRun.sync(
        run, runMode, DriveRule.SHAREPOINT, settings.libraries(), new SharePointWording());
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
