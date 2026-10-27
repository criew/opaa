package io.opaa.indexing.chunk;

import io.opaa.indexing.chunk.EmbeddingIndexState.Snapshot;
import io.opaa.instance.InvalidationBus;
import io.opaa.instance.InvalidationSubscriber;
import io.opaa.llm.EmbeddingInfo;
import io.opaa.llm.EmbeddingInfoService;
import io.opaa.llm.InstalledEmbeddingModel;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Binds {@link EmbeddingIndexState} to the {@link InvalidationBus} (ADR-0053, Entscheidung 5): when
 * another instance changes the model row or the index record - a model change, a discard, a switch,
 * an endpoint change - this instance re-reads both, under the same exclusion as a local switch. The
 * configured identity is the one of the model row, never the one this instance started with. While
 * the bus does not listen, both are re-read at every check of the bus. A target generation a switch
 * of the previous version left behind is adopted right after, by a reconciliation.
 */
@Component
class EmbeddingIndexStateRefresh implements InvalidationSubscriber {

  /** The topic the trigger on {@code embedding_index} announces. */
  static final String INVALIDATION_TOPIC = "embedding-index";

  private final EmbeddingIndexState state;
  private final EmbeddingIndexStore store;
  private final EmbeddingInfoService embeddingInfoService;
  private final EmbeddingIndexReconciler reconciler;

  EmbeddingIndexStateRefresh(
      EmbeddingIndexState state,
      EmbeddingIndexStore store,
      EmbeddingInfoService embeddingInfoService,
      EmbeddingIndexReconciler reconciler) {
    this.state = state;
    this.store = store;
    this.embeddingInfoService = embeddingInfoService;
    this.reconciler = reconciler;
  }

  @Override
  public Set<String> invalidationTopics() {
    return Set.of(INVALIDATION_TOPIC, InstalledEmbeddingModel.INVALIDATION_TOPIC);
  }

  @Override
  public void invalidateAll() {
    // read under the exclusion, so a switch of this instance cannot fall between read and replace
    state.exclusively(
        () -> {
          EmbeddingInfo info = embeddingInfoService.getEmbeddingInfo();
          store
              .load()
              .ifPresent(
                  record ->
                      state.replace(
                          new Snapshot(
                              record.current(),
                              info.installed() ? configuredOf(info) : configuredOf(record),
                              record.target())));
          return null;
        });
    reconciler.adoptSwitchedTargetGeneration();
  }

  private static EmbeddingIdentity configuredOf(EmbeddingInfo info) {
    return new EmbeddingIdentity(
        info.model(), info.baseUrl(), info.dimensions(), info.generation());
  }

  private static EmbeddingIdentity configuredOf(EmbeddingIndexRecord record) {
    return record.target() == null ? record.current() : record.target();
  }
}
