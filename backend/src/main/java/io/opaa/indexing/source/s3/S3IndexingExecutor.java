package io.opaa.indexing.source.s3;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.IndexingRunFailedException;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.library.KnowledgeLibrary;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link IndexingSourceType#S3} (ADR-0027): exactly one mode, the full
 * sync ({@link S3FullSync}) that lists every scope completely, fetches what its change feature says
 * has changed, and reports a complete listing so the run frame removes what it did not meet. The
 * run's store is bounded by the request budget and closed with the run; its throttling and request
 * cost are reported whether the sync succeeded or not.
 */
public class S3IndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(S3IndexingExecutor.class);

  private final S3ClientFactory clientFactory;
  private final S3Properties properties;
  private final FileProcessingService fileProcessingService;
  private final IndexingRunTemplate runTemplate;

  public S3IndexingExecutor(
      S3ClientFactory clientFactory,
      S3Properties properties,
      FileProcessingService fileProcessingService,
      IndexingRunTemplate runTemplate) {
    this.clientFactory = clientFactory;
    this.properties = properties;
    this.fileProcessingService = fileProcessingService;
    this.runTemplate = runTemplate;
  }

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.S3;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    // ADR-0027, Entscheidung 3: no incremental mode - the full listing is cheap enough to be the
    // regular one.
    return Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE);
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    runTemplate.run(jobId, targetLibrary, runMode, this, this::indexScopes);
  }

  /**
   * Opens the run's store from the library's stored configuration and runs the full sync over it. A
   * defect in the configuration or a store the access layer cannot open fails the run with the
   * layer's own German sentence, before any object is touched.
   */
  ListingOutcome indexScopes(IndexingRun run) throws InterruptedException {
    KnowledgeLibrary library = run.library();
    S3SourceSettings settings = library.getS3Settings();
    S3Connection connection;
    try {
      connection = S3LibraryConnection.of(library, settings);
    } catch (S3LibraryConnection.InvalidS3ConfigurationException e) {
      throw new IndexingRunFailedException(e.getMessage());
    }
    S3ObjectStore store;
    try {
      store = clientFactory.createForRun(connection, settings.scopes());
    } catch (S3AccessException e) {
      throw accessFailure(run, e);
    }
    try (store) {
      return new S3FullSync(run, store, settings, properties, fileProcessingService)
          .run(settings.scopes());
    } finally {
      reportThrottling(store, run.events());
      S3RequestMeter meter = store.meter();
      run.recordRequestCost(meter.requests(), meter.throttles(), meter.throttledTime().toMillis());
    }
  }

  private static IndexingRunFailedException accessFailure(IndexingRun run, S3AccessException e) {
    log.warn("S3 run for library {} failed: {}", run.library().getId(), e.getMessage());
    return new IndexingRunFailedException(e.getMessage(), e);
  }

  private static void reportThrottling(S3ObjectStore store, IndexingRunEventRecorder events) {
    S3RequestMeter meter = store.meter();
    if (meter.throttles() == 0) {
      return;
    }
    events.record(
        IndexingEventCategory.RATE_LIMITED,
        "Der Objektspeicher hat den Lauf "
            + meter.throttles()
            + "-mal gedrosselt (503 SlowDown/429); der Lauf hat insgesamt "
            + meter.throttledTime().toSeconds()
            + " Sekunden gewartet statt abzubrechen",
        null);
  }
}
