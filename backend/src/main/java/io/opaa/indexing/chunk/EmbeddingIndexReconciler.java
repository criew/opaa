package io.opaa.indexing.chunk;

import io.opaa.indexing.chunk.EmbeddingIndexRecord.Run;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.RunStatus;
import io.opaa.indexing.chunk.EmbeddingIndexState.Snapshot;
import io.opaa.llm.EmbeddingInfo;
import io.opaa.llm.EmbeddingInfoService;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Compares the configured embedding model with the one the vector index was built with, once all
 * beans exist (so after Liquibase and after {@code PgVectorStore} created its table) and before the
 * application serves requests. Never refuses the start and never starts a run.
 *
 * <p>A mismatch in model or size marks the index stale and prepares an empty target column of the
 * configured size, unless one for exactly this model already exists. An empty table is resized in
 * place. An index without a recorded identity takes the configuration's when its size matches.
 * Going back to the model of the index drops the target column.
 */
@Component
public class EmbeddingIndexReconciler implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexReconciler.class);

  static final String TARGET_CHANGED_MESSAGE =
      "Abgebrochen: Das konfigurierte Embedding-Modell hat sich seit dem Start geändert.";

  static final String NO_LONGER_STALE_MESSAGE =
      "Abgebrochen: Der Index entspricht wieder dem konfigurierten Embedding-Modell.";

  private final EmbeddingIndexState state;
  private final EmbeddingIndexStore store;
  private final VectorIndexSchema schema;
  private final EmbeddingInfoService embeddingInfoService;

  public EmbeddingIndexReconciler(
      EmbeddingIndexState state,
      EmbeddingIndexStore store,
      VectorIndexSchema schema,
      EmbeddingInfoService embeddingInfoService) {
    this.state = state;
    this.store = store;
    this.schema = schema;
    this.embeddingInfoService = embeddingInfoService;
  }

  @Override
  public void afterSingletonsInstantiated() {
    reconcile();
  }

  public void reconcile() {
    EmbeddingInfo info = embeddingInfoService.getEmbeddingInfo();
    EmbeddingIdentity configured =
        new EmbeddingIdentity(info.model(), info.baseUrl(), info.dimensions());
    state.exclusively(
        () -> {
          state.replace(reconcile(configured));
          return null;
        });
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
              "The configured embedding model matches the index again; {} chunk(s) written while"
                  + " it was stale have no vector and stay findable by full-text search only"
                  + " until their documents are indexed again",
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

  private static Run endIfRunning(Run run, String message) {
    return run.running() ? run.ended(RunStatus.CANCELLED, Instant.now(), message) : run;
  }
}
