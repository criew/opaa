package io.opaa.indexing.source.s3;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.run.IndexingRun;
import io.opaa.indexing.run.IndexingRunTemplate;
import io.opaa.indexing.run.ResolvedScopes;
import io.opaa.indexing.spi.ConnectorData;
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
import io.opaa.s3.S3AccessException;
import io.opaa.s3.S3Connection;
import io.opaa.s3.S3Credentials;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link S3SourceConnector#TYPE} (ADR-0027): exactly one mode, the full
 * sync ({@link S3FullSync}) that lists every scope completely, fetches what its change feature says
 * has changed, and reports a complete listing so the run frame removes what it did not meet. The
 * key prefixes below each scope are mirrored as read-only folders (ADR-0020, ADR-0027 Entscheidung
 * 5) and pruned after the reconciliation. The run's store is bounded by the request budget and
 * closed with the run; its request meter is handed to the frame, which reports throttling and cost
 * whether the sync succeeded or not.
 */
public class S3IndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(S3IndexingExecutor.class);

  static final String NO_VISIBLE_BUCKET =
      "Der Zugang sieht keinen Bucket; der Umfang „Alle Buckets“ ist leer. Der Bestand bleibt"
          + " unverändert.";

  static final String NO_READABLE_EVENT_BUCKET =
      "Ereignislauf: Keiner der gemeldeten Buckets ist für die Bibliothek lesbar - nichts zu"
          + " prüfen.";

  private final S3ClientFactory clientFactory;
  private final S3Properties properties;
  private final DocumentIngestService documentIngestService;
  private final DocumentRepository documentRepository;
  private final LibraryFolderService folderService;
  private final ScanJournal journal;
  private final Clock clock;
  private final IndexingRunTemplate runTemplate;
  private final SupportedDocumentFormats supportedFormats;

  public S3IndexingExecutor(
      S3ClientFactory clientFactory,
      S3Properties properties,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService folderService,
      ScanJournal journal,
      Clock clock,
      IndexingRunTemplate runTemplate,
      SupportedDocumentFormats supportedFormats) {
    this.clientFactory = clientFactory;
    this.properties = properties;
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
    return S3SourceConnector.TYPE;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    // ADR-0027, Entscheidung 3: no incremental mode - the full listing is cheap enough to be the
    // regular one; the event run checks named keys only and never removes by absence.
    return Map.of(
        IndexingRunMode.FULL,
        VanishedDocumentPolicy.REMOVE_ON_ABSENCE,
        IndexingRunMode.EVENT,
        VanishedDocumentPolicy.KEEP_ON_ABSENCE);
  }

  /** Always the full sync: the event run is started by a notification alone, never by default. */
  @Override
  public IndexingRunMode defaultRunMode(KnowledgeLibrary library, ConnectorData settings) {
    return IndexingRunMode.FULL;
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    if (runMode == IndexingRunMode.EVENT) {
      // an event run without reported keys has nothing to check - the frame ends it cleanly
      runTemplate.run(
          jobId,
          targetLibrary,
          runMode,
          this,
          run -> {
            run.events()
                .recordRunNote(
                    IndexingEventCategory.SUMMARY,
                    "Ereignislauf ohne gemeldete Objekte - nichts zu prüfen");
            return ListingOutcome.partial();
          });
      return;
    }
    runTemplate.run(jobId, targetLibrary, runMode, this, this::indexScopes);
  }

  /**
   * The event run (ADR-0027, Entscheidung 6): checks exactly {@code references} ({@code
   * bucket/key}) with one {@code HeadObject} each - a changed object goes the full sync's way, a
   * {@code 404} removes the document with its attachments, anything else changes nothing. Never a
   * listing, never a reconciliation, and the resumption state stays untouched. {@code dropped}
   * events outside the scopes are noted once.
   */
  @Async("indexingTaskExecutor")
  public void refreshObjects(
      UUID jobId, KnowledgeLibrary targetLibrary, Set<String> references, int dropped) {
    runTemplate.run(
        jobId,
        targetLibrary,
        IndexingRunMode.EVENT,
        this,
        run -> withStore(run, bucketsOf(references), sync -> sync.refresh(references, dropped)));
  }

  /** The buckets {@code references} ({@code bucket/key}) name. */
  private static Set<String> bucketsOf(Set<String> references) {
    Set<String> buckets = new TreeSet<>();
    for (String reference : references) {
      int slash = reference.indexOf('/');
      buckets.add(slash < 0 ? reference : reference.substring(0, slash));
    }
    return buckets;
  }

  /** One run body over the open store and sync. */
  @FunctionalInterface
  private interface SyncBody {
    ListingOutcome run(S3FullSync sync) throws InterruptedException;
  }

  /**
   * Opens the run's store from the resolved configuration and runs the full sync over it. A defect
   * in the configuration or a store the access layer cannot open fails the run with the layer's own
   * German sentence, before any object is touched.
   */
  ListingOutcome indexScopes(IndexingRun run) throws InterruptedException {
    return withStore(run, null, S3FullSync::run);
  }

  /**
   * @param eventBuckets the buckets an event run's keys name, {@code null} for the full sync
   */
  private ListingOutcome withStore(IndexingRun run, Set<String> eventBuckets, SyncBody body)
      throws InterruptedException {
    KnowledgeLibrary library = run.library();
    S3SourceSettings settings = S3SourceSettingsJson.of(run.settings().connectorSettings());
    S3Connection connection;
    try {
      connection = S3LibraryConnection.of(run.settings(), run.credentials().value(), settings);
    } catch (S3LibraryConnection.InvalidS3ConfigurationException e) {
      throw new IndexingRunFailedException(e.getMessage());
    }
    S3ObjectStore store;
    try {
      store =
          clientFactory.createForRun(
              connection,
              settings.scopes(),
              run.credentials().renewableAfterRejection(S3Credentials::parse));
    } catch (S3AccessException e) {
      throw accessFailure(run, e);
    }
    run.recordRequestCost(store.meter());
    UUID libraryId = library.getId();
    SourceSyncState state = journal.load(libraryId);
    Map<String, String> withheld = Map.of();
    if (settings.allBuckets()) {
      try {
        S3AllBuckets.Resolution resolution =
            resolveAllBuckets(run, store, connection, settings, eventBuckets);
        if (eventBuckets == null) {
          Map<String, String> names = new LinkedHashMap<>();
          resolution.inScope().forEach(bucket -> names.put(bucket, bucket));
          ResolvedScopes.note(run, state, names, resolution.outside(), "Bucket", "Buckets");
          state = journal.save(run.jobId(), state);
          withheld = new LinkedHashMap<>();
          for (Map.Entry<String, String> entry : resolution.withheld().entrySet()) {
            withheld.put(
                entry.getKey(), S3AllBuckets.withheldMessage(entry.getKey(), entry.getValue()));
          }
          settings = settings.resolvedTo(resolution.inScope());
        } else if (resolution.readable().isEmpty()) {
          // stored "Alle Buckets" settings without scopes would admit any bucket
          store.close();
          run.events().recordRunNote(IndexingEventCategory.SUMMARY, NO_READABLE_EVENT_BUCKET);
          return ListingOutcome.partial();
        } else {
          settings = settings.resolvedTo(resolution.readable());
        }
      } catch (RuntimeException | InterruptedException e) {
        store.close();
        throw e;
      }
    }
    try (S3FileStore fileStore = new S3FileStore(store, settings, withheld);
        S3FullSync sync =
            new S3FullSync(
                run,
                fileStore,
                settings,
                properties,
                documentIngestService,
                documentRepository,
                folderService,
                state,
                journal,
                clock,
                supportedFormats)) {
      return body.run(sync);
    }
  }

  /**
   * "Alle Buckets" as this run finds it ({@link S3AllBuckets}): every bucket of the connection's
   * region the key may list, plus those it cannot read but the library holds documents from. An
   * event run probes only {@code eventBuckets} and leaves the remembered resolution alone. A key
   * without {@code s3:ListAllMyBuckets} or a full resolution without any readable bucket fails the
   * run before any object is touched, so nothing indexed is removed.
   */
  private S3AllBuckets.Resolution resolveAllBuckets(
      IndexingRun run,
      S3ObjectStore store,
      S3Connection connection,
      S3SourceSettings settings,
      Set<String> eventBuckets)
      throws InterruptedException {
    S3BucketListing listing;
    try {
      listing = store.listBuckets();
    } catch (S3AccessException e) {
      throw accessFailure(run, e);
    }
    if (!(listing instanceof S3BucketListing.Listed listed)) {
      throw new IndexingRunFailedException(S3SourceConnector.ALL_BUCKETS_NOT_LISTABLE);
    }
    if (listed.names().isEmpty()) {
      throw new IndexingRunFailedException(NO_VISIBLE_BUCKET);
    }
    if (eventBuckets != null) {
      listed =
          new S3BucketListing.Listed(
              listed.names().stream().filter(eventBuckets::contains).toList(), listed.regions());
    }
    UUID libraryId = run.library().getId();
    S3AllBuckets.Resolution resolution;
    try {
      clientFactory.validateTargets(connection, settings.resolvedTo(namedBuckets(listed)).scopes());
      resolution =
          S3AllBuckets.resolve(
              store,
              listed,
              settings.effectiveRegion(),
              bucket ->
                  documentRepository.existsByLibraryIdAndFilePathStartingWith(
                      libraryId, S3ObjectRef.filePath(bucket, "")),
              () -> false);
    } catch (S3AccessException e) {
      throw accessFailure(run, e);
    }
    if (eventBuckets == null && resolution.readable().isEmpty()) {
      throw new IndexingRunFailedException(noReadableBucket(resolution));
    }
    return resolution;
  }

  /** The listed names that are bucket names at all; the others never get a request. */
  private static List<String> namedBuckets(S3BucketListing.Listed listed) {
    List<String> buckets = new ArrayList<>();
    for (String name : new TreeSet<>(listed.names())) {
      try {
        buckets.add(S3Scope.validateBucket(name));
      } catch (S3Scope.InvalidS3ScopeException e) {
        // left out with its reason by S3AllBuckets
      }
    }
    return buckets;
  }

  /** The run's failure when the key sees buckets but the library may read none of them. */
  static String noReadableBucket(S3AllBuckets.Resolution resolution) {
    int count = resolution.seen();
    return "Der Zugang sieht "
        + (count == 1 ? "1 Bucket" : count + " Buckets")
        + ", aber keinen davon kann die Bibliothek lesen: "
        + resolution.unreadableNamed()
        + ". Der Bestand bleibt unverändert.";
  }

  private static IndexingRunFailedException accessFailure(IndexingRun run, S3AccessException e) {
    log.warn("S3 run for library {} failed: {}", run.library().getId(), e.getMessage());
    return new IndexingRunFailedException(e.getMessage(), e);
  }
}
