package io.opaa.indexing.source.sharepoint;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.filesync.FileSync;
import io.opaa.indexing.filesync.FileSyncSettings;
import io.opaa.indexing.filesync.FileSyncWording;
import io.opaa.indexing.run.IndexingRun;
import io.opaa.indexing.spi.IndexingRunFailedException;
import io.opaa.indexing.spi.ListingOutcome;
import io.opaa.indexing.spi.RequestBudget;
import io.opaa.indexing.spi.ScanJournal;
import io.opaa.indexing.spi.SourceCredentialsRejectedException;
import io.opaa.indexing.spi.SourceSyncState;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.msgraph.GraphClient;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The run body every Graph drive executor shares: one budget and one {@link DriveFileStore} per
 * run, synced by {@link FileSync}, in full or from the change log. It never runs on its own - an
 * executor calls it from its own body, the only one {@code IndexingRunTemplate} admits.
 */
final class DriveRun {

  private final GraphConnections graphs;
  private final DocumentIngestService documentIngestService;
  private final DocumentRepository documentRepository;
  private final LibraryFolderService folderService;
  private final ScanJournal journal;
  private final Clock clock;
  private final SupportedDocumentFormats supportedFormats;

  DriveRun(
      GraphConnections graphs,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService folderService,
      ScanJournal journal,
      Clock clock,
      SupportedDocumentFormats supportedFormats) {
    this.graphs = graphs;
    this.documentIngestService = documentIngestService;
    this.documentRepository = documentRepository;
    this.folderService = folderService;
    this.journal = journal;
    this.clock = clock;
    this.supportedFormats = supportedFormats;
  }

  /** What a run asks Graph before its first request that names one of its drives. */
  @FunctionalInterface
  interface Admission {

    /** Admits every drive. */
    Admission ANY = graph -> {};

    /**
     * Refuses the whole run: a rejected token as {@link FileAccessException.CredentialsRejected},
     * anything else with the German reason as its message.
     */
    void require(GraphClient graph) throws FileAccessException, InterruptedException;
  }

  /**
   * Syncs {@code drives} of {@code run}, each of the kind {@code rule} admits, once {@code
   * admission} let the run through; a refused run fails without touching its documents.
   */
  ListingOutcome sync(
      IndexingRun run,
      IndexingRunMode runMode,
      DriveRule rule,
      List<SharePointLibrary> drives,
      FileSyncWording wording,
      Admission admission)
      throws InterruptedException {
    SharePointProperties properties = graphs.properties();
    RequestBudget budget =
        new RequestBudget(
            new SourceRequestMeter(),
            properties.requestBudgetPerRun(),
            properties.maxThrottleWaitPerRun());
    GraphClient graph = graphs.open(run.settings(), run.credentials(), budget);
    run.recordRequestCost(budget.meter());
    try {
      admission.require(graph);
    } catch (FileAccessException.CredentialsRejected e) {
      throw new SourceCredentialsRejectedException(e.getMessage(), e);
    } catch (FileAccessException e) {
      throw new IndexingRunFailedException(e.getMessage(), e);
    }
    UUID libraryId = run.library().getId();
    SourceSyncState state = journal.load(libraryId);
    try (DriveFileStore store =
            new DriveFileStore(rule, graph, budget.meter(), drives, properties.pageSize());
        FileSync sync =
            new FileSync(
                run,
                store,
                new FileSyncSettings(
                    properties.maxFileSizeBytes(),
                    properties.maxFilesPerRun(),
                    properties.downloadConcurrency(),
                    rule.scheme() + "-download-"),
                wording,
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
}
