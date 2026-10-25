package io.opaa.indexing.chunk;

import io.opaa.indexing.chunk.EmbeddingIndexRecord.Run;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.RunStatus;
import io.opaa.indexing.chunk.EmbeddingIndexState.Snapshot;
import io.opaa.instance.CurrentInstance;
import io.opaa.llm.EmbeddingInfo;
import io.opaa.llm.EmbeddingInfoService;
import io.opaa.llm.EmbeddingModelInstalledEvent;
import io.opaa.llm.EmbeddingModelService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Compares the configured embedding model with the one the vector index was built with, at the
 * start right after this instance was admitted to the instance register and before it serves
 * requests or runs a task. Never refuses the start and never starts a run.
 *
 * <p>Several instances share the vector table (ADR-0053, Entscheidung 8): the comparison runs in
 * one transaction under {@link VectorIndexSchema#lockSchemaChanges}, and only an instance of the
 * newest live version changes the table or the record. An older one, such as a pod of the previous
 * version restarting during a rolling update, takes the index as recorded. The register admits no
 * instance whose embedding identity differs from a live one's (Entscheidung 4).
 *
 * <p>A mismatch in model or size marks the index stale and prepares an empty target column of the
 * configured size, unless one for exactly this model already exists. An empty table is resized in
 * place. An index without a recorded identity takes the configuration's when its size matches.
 * Going back to the model of the index drops the target column.
 *
 * <p>Without an installed embedding model there is no vector table and nothing to compare: the
 * start first lets {@link EmbeddingModelService#adoptAtStartup()} take over the model of an older
 * installation, and the reconciliation runs once a model is installed, at the latest after the
 * commit that installed it.
 */
@Component
public class EmbeddingIndexReconciler implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexReconciler.class);

  static final String TARGET_CHANGED_MESSAGE =
      "Abgebrochen: Das konfigurierte Embedding-Modell hat sich seit dem Start geändert.";

  static final String NO_LONGER_STALE_MESSAGE =
      "Abgebrochen: Der Index entspricht wieder dem konfigurierten Embedding-Modell.";

  private final EmbeddingIndexState state;
  private final EmbeddingIndexStore store;
  private final VectorIndexSchema schema;
  private final EmbeddingInfoService embeddingInfoService;
  private final EmbeddingModelService embeddingModels;
  private final Clock clock;
  private final CurrentInstance currentInstance;
  private final TransactionTemplate transactions;
  private volatile boolean running;

  public EmbeddingIndexReconciler(
      EmbeddingIndexState state,
      EmbeddingIndexStore store,
      VectorIndexSchema schema,
      EmbeddingInfoService embeddingInfoService,
      EmbeddingModelService embeddingModels,
      Clock clock,
      CurrentInstance currentInstance,
      PlatformTransactionManager transactionManager) {
    this.state = state;
    this.store = store;
    this.schema = schema;
    this.embeddingInfoService = embeddingInfoService;
    this.embeddingModels = embeddingModels;
    this.clock = clock;
    this.currentInstance = currentInstance;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public void start() {
    if (embeddingModels.adoptAtStartup()) {
      reconcile();
    } else {
      log.warn(
          "No embedding model is installed: there is no vector table yet, indexing and search are"
              + " refused until the installation chooses one");
    }
    running = true;
  }

  /** Reconciles the index of a model this instance installed while running. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void onEmbeddingModelInstalled(EmbeddingModelInstalledEvent event) {
    if (running) {
      reconcile();
    }
  }

  @Override
  public void stop() {
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return CurrentInstance.REGISTERED_PHASE;
  }

  public void reconcile() {
    EmbeddingInfo info = embeddingInfoService.getEmbeddingInfo();
    if (!info.installed()) {
      return;
    }
    EmbeddingIdentity configured =
        new EmbeddingIdentity(info.model(), info.baseUrl(), info.dimensions());
    state.exclusively(
        () -> {
          state.replace(
              transactions.execute(
                  status -> {
                    schema.lockSchemaChanges();
                    return currentInstance.runsNewestVersion()
                        ? reconcile(configured)
                        : asRecorded(configured);
                  }));
          return null;
        });
  }

  /**
   * The index as the record of the newest version describes it, without any change; an index nobody
   * recorded yet counts as built with the configured model.
   */
  private Snapshot asRecorded(EmbeddingIdentity configured) {
    log.info(
        "A newer version runs next to this instance; the vector index is taken as recorded,"
            + " without reconciling it");
    return store
        .load()
        .map(record -> new Snapshot(record.current(), configured, record.target()))
        .orElse(new Snapshot(configured, configured, null));
  }

  private Snapshot reconcile(EmbeddingIdentity configured) {
    Optional<EmbeddingIndexRecord> recorded = store.load();
    EmbeddingIndexRecord stored =
        recorded.orElse(new EmbeddingIndexRecord(configured, null, Run.NONE));
    Integer columnDimensions = schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN);

    if (columnDimensions == null || !schema.hasChunks()) {
      if (columnDimensions != null
          && columnDimensions != -1
          && columnDimensions != configured.dimensions()) {
        schema.resizeEmptyEmbeddingColumn(configured.dimensions());
      }
      if (columnDimensions != null) {
        schema.dropTargetColumn();
      }
      return current(stored, configured, NO_LONGER_STALE_MESSAGE);
    }

    int indexDimensions = columnDimensions == -1 ? configured.dimensions() : columnDimensions;
    EmbeddingIdentity built =
        recorded.isPresent()
            ? new EmbeddingIdentity(
                stored.current().model(), stored.current().baseUrl(), indexDimensions)
            : indexDimensions == configured.dimensions()
                ? configured
                : new EmbeddingIdentity(null, null, indexDimensions);

    if (built.sameModelAs(configured)) {
      if (schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN) != null) {
        long withoutVector = schema.chunksWithout(EmbeddingIndexState.EMBEDDING_COLUMN);
        schema.dropTargetColumn();
        if (withoutVector > 0) {
          log.warn(
              "The configured embedding model matches the index again; {} chunk(s) newly written"
                  + " or rewritten with another text while it was stale have no vector and are"
                  + " found by the full-text search only",
              withoutVector);
        }
      }
      return current(stored, configured, NO_LONGER_STALE_MESSAGE);
    }

    EmbeddingIdentity target = stored.target();
    Integer targetDimensions = schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN);
    Run run = stored.run();
    boolean targetKept =
        target != null
            && target.sameModelAs(configured)
            && targetDimensions != null
            && targetDimensions == configured.dimensions();
    if (!targetKept) {
      schema.recreateTargetColumn(configured.dimensions());
      run = endIfRunning(run, TARGET_CHANGED_MESSAGE);
    }
    log.warn(
        "The vector index was built with embedding model {} ({} dimensions), but {} ({} dimensions)"
            + " is configured: the vector search is suspended until the index has been re-embedded"
            + " from the administration",
        built.model() == null ? "(unknown)" : built.model(),
        built.dimensions(),
        configured.model(),
        configured.dimensions());
    store.save(new EmbeddingIndexRecord(built, configured, run));
    return new Snapshot(built, configured, configured);
  }

  /** The index matches the configuration: no target, a running run has nothing left to do. */
  private Snapshot current(
      EmbeddingIndexRecord stored, EmbeddingIdentity configured, String endMessage) {
    store.save(new EmbeddingIndexRecord(configured, null, endIfRunning(stored.run(), endMessage)));
    return new Snapshot(configured, configured, null);
  }

  private Run endIfRunning(Run run, String message) {
    return run.running() ? run.ended(RunStatus.CANCELLED, Instant.now(clock), message) : run;
  }
}
