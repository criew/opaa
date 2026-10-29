package io.opaa.indexing.source.googledrive;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.filesync.FileSync;
import io.opaa.indexing.filesync.FileSyncSettings;
import io.opaa.indexing.run.IndexingRun;
import io.opaa.indexing.run.IndexingRunTemplate;
import io.opaa.indexing.run.ResolvedScopes;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.FullSyncRhythm;
import io.opaa.indexing.spi.IndexingRunFailedException;
import io.opaa.indexing.spi.ListingOutcome;
import io.opaa.indexing.spi.RequestBudget;
import io.opaa.indexing.spi.ScanJournal;
import io.opaa.indexing.spi.SourceCredentialsRejectedException;
import io.opaa.indexing.spi.SourceIndexingExecutor;
import io.opaa.indexing.spi.SourceSyncState;
import io.opaa.indexing.spi.VanishedDocumentPolicy;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceType;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Async;

/**
 * Runs a Google Drive library (ADR-0040, Entscheidung 6): a full sync that lists every scope and
 * removes what it did not meet, or a change run that reads the change streams from their cursors.
 * The change run is the default while a full sync completed within the rhythm and every stream has
 * a cursor. Under "Alle Ablagen" every run resolves the scopes anew ({@link AllDriveScopes}); the
 * default run mode holds the streams of the last resolution.
 */
public class GoogleDriveIndexingExecutor implements SourceIndexingExecutor {

  private final DriveApiFactory apis;
  private final DocumentIngestService documentIngestService;
  private final DocumentRepository documentRepository;
  private final LibraryFolderService folderService;
  private final ScanJournal journal;
  private final FullSyncRhythm rhythm;
  private final Clock clock;
  private final IndexingRunTemplate runTemplate;
  private final SupportedDocumentFormats supportedFormats;

  public GoogleDriveIndexingExecutor(
      DriveApiFactory apis,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService folderService,
      ScanJournal journal,
      FullSyncRhythm rhythm,
      Clock clock,
      IndexingRunTemplate runTemplate,
      SupportedDocumentFormats supportedFormats) {
    this.apis = apis;
    this.documentIngestService = documentIngestService;
    this.documentRepository = documentRepository;
    this.folderService = folderService;
    this.journal = journal;
    this.rhythm = rhythm;
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
    Duration interval = interval(driveSettings);
    return journal
        .find(library.getId())
        .filter(
            state -> {
              Set<String> streams = streams(driveSettings, state);
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
    DriveApi api = apis.open(run.settings(), run.credentials(), budget);
    run.recordRequestCost(budget.meter());
    UUID libraryId = run.library().getId();
    SourceSyncState state = journal.load(libraryId);
    if (settings.allScopes()) {
      settings = resolve(run, api, settings, state);
      state = journal.save(run.jobId(), state);
    }
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
                state,
                journal,
                clock,
                supportedFormats)) {
      return runMode == IndexingRunMode.INCREMENTAL ? sync.runChanges() : sync.run();
    }
  }

  /**
   * "Alle Ablagen" as this run finds it, noted in the protocol against the last resolution. A
   * listing that fails or finds nothing fails the run before any file is touched.
   */
  private static GoogleDriveSettings resolve(
      IndexingRun run, DriveApi api, GoogleDriveSettings settings, SourceSyncState state)
      throws InterruptedException {
    List<GoogleDriveScope> found;
    try {
      found = AllDriveScopes.resolve(api, settings.subject());
    } catch (DriveApiException e) {
      if (e.kind() == DriveApiException.Kind.UNAUTHORIZED) {
        throw new SourceCredentialsRejectedException(e.getMessage(), e);
      }
      throw new IndexingRunFailedException(e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new IndexingRunFailedException(e.getMessage(), e);
    }
    if (found.isEmpty()) {
      throw new IndexingRunFailedException(GoogleDriveSourceConnector.NO_VISIBLE_SCOPE);
    }
    ResolvedScopes.note(run, state, AllDriveScopes.named(found), "Ablage", "Ablagen");
    return settings.resolvedTo(found);
  }

  private Duration interval(GoogleDriveSettings settings) {
    return rhythm.interval(
        GoogleDriveSourceConnector.TYPE,
        GoogleDriveSourceConnector.FULL_SYNC_INTERVAL,
        settings.fullSyncIntervalDays());
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

  /**
   * The change streams of {@code settings}; under "Alle Ablagen" those of the resolution {@code
   * state} remembers, {@code null} before the first one.
   */
  static Set<String> streams(GoogleDriveSettings settings, SourceSyncState state) {
    if (!settings.allScopes()) {
      return streams(settings);
    }
    Map<String, String> resolved = state.resolvedScopes();
    if (resolved == null) {
      return null;
    }
    return resolved.keySet().stream()
        .map(key -> key.equals(GoogleDriveScope.MY_DRIVE_KEY) ? DriveFileStore.USER_STREAM : key)
        .collect(Collectors.toSet());
  }
}
