package io.opaa.indexing.source.sharepoint;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.run.IndexingRun;
import io.opaa.indexing.run.IndexingRunTemplate;
import io.opaa.indexing.run.ResolvedScopes;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.FullSyncRhythm;
import io.opaa.indexing.spi.IndexingRunFailedException;
import io.opaa.indexing.spi.ListingOutcome;
import io.opaa.indexing.spi.ScanJournal;
import io.opaa.indexing.spi.SourceIndexingExecutor;
import io.opaa.indexing.spi.SourceSyncState;
import io.opaa.indexing.spi.VanishedDocumentPolicy;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceType;
import io.opaa.msgraph.GraphClient;
import io.opaa.msgraph.GraphException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Async;

/**
 * Runs a SharePoint library (ADR-0047): a full sync that lists every document library, possibly
 * over several runs, and removes what neither it nor the change log saw; or a change run that reads
 * each library's delta from its cursor. The change run is the default while a full sync completed
 * within the rhythm and every library has a cursor. A site taken whole is resolved anew by every
 * run ({@link SiteLibraries}); the default run mode holds the libraries of the last resolution.
 */
public class SharePointIndexingExecutor implements SourceIndexingExecutor {

  static final String NO_VISIBLE_LIBRARY =
      "Die gewählten Sites haben keine Dokumentbibliothek, die die Anwendung sieht. Der Bestand"
          + " bleibt unverändert.";

  private final GraphConnections graphs;
  private final ScanJournal journal;
  private final FullSyncRhythm rhythm;
  private final Clock clock;
  private final IndexingRunTemplate runTemplate;
  private final DriveRun driveRun;

  SharePointIndexingExecutor(
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
    Duration interval = interval(sharePointSettings);
    return journal
        .find(library.getId())
        .filter(
            state -> {
              Set<String> streams = streams(sharePointSettings, state);
              return streams != null && state.canReadChanges(streams, interval, clock.instant());
            })
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
    if (!settings.hasWholeSites()) {
      return driveRun.sync(
          run,
          runMode,
          DriveRule.SHAREPOINT,
          settings.libraries(),
          new SharePointWording(),
          DriveRun.Admission.ANY);
    }
    SharePointSettings chosen = settings;
    return driveRun.sync(
        run,
        runMode,
        DriveRule.SHAREPOINT,
        (graph, state) -> resolve(run, graph, chosen, state),
        new SharePointWording(),
        DriveRun.Admission.ANY);
  }

  /**
   * The chosen libraries and those of the sites taken whole, as this run finds them, noted in the
   * protocol against the last resolution. A site without any document library fails the run before
   * any file is touched.
   */
  private static List<SharePointLibrary> resolve(
      IndexingRun run, GraphClient graph, SharePointSettings settings, SourceSyncState state)
      throws FileAccessException, InterruptedException {
    List<SharePointLibrary> found;
    try {
      found = SiteLibraries.resolve(graph, settings.sites());
    } catch (GraphException e) {
      if (e.kind() == GraphException.Kind.UNAUTHORIZED) {
        throw new FileAccessException.CredentialsRejected(e.getMessage());
      }
      throw new FileAccessException.RunEnding(e.getMessage());
    }
    if (found.isEmpty()) {
      throw new FileAccessException.RunEnding(NO_VISIBLE_LIBRARY);
    }
    ResolvedScopes.note(
        run, state, SiteLibraries.named(found), "Dokumentbibliothek", "Dokumentbibliotheken");
    return settings.resolvedTo(found).libraries();
  }

  private Duration interval(SharePointSettings settings) {
    return rhythm.interval(
        SharePointSourceConnector.TYPE,
        SharePointSourceConnector.FULL_SYNC_INTERVAL,
        settings.fullSyncIntervalDays());
  }

  /** One change stream per document library, keyed like its container. */
  static Set<String> streams(SharePointSettings settings) {
    return settings.libraries().stream().map(SharePointLibrary::key).collect(Collectors.toSet());
  }

  /**
   * The change streams of {@code settings}; with a site taken whole also those of the resolution
   * {@code state} remembers, {@code null} before the first one.
   */
  static Set<String> streams(SharePointSettings settings, SourceSyncState state) {
    if (!settings.hasWholeSites()) {
      return streams(settings);
    }
    Map<String, String> resolved = state.resolvedScopes();
    if (resolved == null) {
      return null;
    }
    Set<String> streams = new HashSet<>(streams(settings));
    streams.addAll(resolved.keySet());
    return streams;
  }
}
