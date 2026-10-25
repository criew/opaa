package io.opaa.indexing.source.sharepoint;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.run.IndexingRun;
import io.opaa.indexing.run.IndexingRunTemplate;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.FullSyncRhythm;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;

/**
 * Runs a OneDrive library (ADR-0058) like a SharePoint one with a single drive: a full sync that
 * removes what neither it nor the change log saw, or a change run from the drive's cursor. No
 * request names the drive before {@link OwnDrive#require} found it to be the token's own.
 */
public class OneDriveIndexingExecutor implements SourceIndexingExecutor {

  private final GraphConnections graphs;
  private final ScanJournal journal;
  private final FullSyncRhythm rhythm;
  private final Clock clock;
  private final IndexingRunTemplate runTemplate;
  private final DriveRun driveRun;

  OneDriveIndexingExecutor(
      GraphConnections graphs,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService folderService,
      ScanJournal journal,
      FullSyncRhythm rhythm,
      Clock clock,
      IndexingRunTemplate runTemplate,
      SupportedDocumentFormats supportedFormats) {
    this.graphs = graphs;
    this.journal = journal;
    this.rhythm = rhythm;
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
    return OneDriveSourceConnector.TYPE;
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
    OneDriveSettings oneDrive;
    try {
      oneDrive = OneDriveSettings.stored(settings);
    } catch (RuntimeException e) {
      return IndexingRunMode.FULL;
    }
    if (oneDrive == null) {
      return IndexingRunMode.FULL;
    }
    Duration interval =
        rhythm.interval(
            OneDriveSourceConnector.TYPE,
            OneDriveSourceConnector.FULL_SYNC_INTERVAL,
            oneDrive.fullSyncIntervalDays());
    return journal
        .find(library.getId())
        .filter(
            state ->
                state.canReadChanges(Set.of(oneDrive.drive().key()), interval, clock.instant()))
        .map(state -> IndexingRunMode.INCREMENTAL)
        .orElse(IndexingRunMode.FULL);
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    runTemplate.run(jobId, targetLibrary, runMode, this, run -> sync(run, runMode));
  }

  ListingOutcome sync(IndexingRun run, IndexingRunMode runMode) throws InterruptedException {
    OneDriveSettings settings;
    try {
      settings = OneDriveSettings.stored(run.settings().connectorSettings());
    } catch (RuntimeException e) {
      throw new IndexingRunFailedException(e.getMessage());
    }
    if (settings == null) {
      throw new IndexingRunFailedException("Die Bibliothek hat kein OneDrive gewählt.");
    }
    SharePointLibrary drive = settings.drive();
    return driveRun.sync(
        run,
        runMode,
        DriveRule.ONEDRIVE,
        List.of(drive),
        new OneDriveWording(),
        graph -> OwnDrive.require(graph, drive.driveId()));
  }
}
