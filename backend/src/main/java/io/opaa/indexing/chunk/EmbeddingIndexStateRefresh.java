package io.opaa.indexing.chunk;

import io.opaa.indexing.chunk.EmbeddingIndexState.Snapshot;
import io.opaa.instance.InvalidationBus;
import io.opaa.instance.InvalidationSubscriber;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Binds {@link EmbeddingIndexState} to the {@link InvalidationBus} (ADR-0053, Entscheidung 5): when
 * another instance switches the index to its target or marks it stale, this instance re-reads the
 * identity and the target from {@code embedding_index}, under the same exclusion as a local switch.
 * The configured identity stays this instance's own; the configuration fingerprint keeps it equal
 * on all instances. While the bus does not listen, the row is re-read at every check of the bus.
 *
 * <p>Before this instance reconciled - the embedding model was installed on another instance after
 * this one started - the row is taken as it is: the installing instance just wrote it for the
 * installed model.
 */
@Component
class EmbeddingIndexStateRefresh implements InvalidationSubscriber {

  /** The topic the trigger on {@code embedding_index} announces. */
  static final String INVALIDATION_TOPIC = "embedding-index";

  private final EmbeddingIndexState state;
  private final EmbeddingIndexStore store;

  EmbeddingIndexStateRefresh(EmbeddingIndexState state, EmbeddingIndexStore store) {
    this.state = state;
    this.store = store;
  }

  @Override
  public Set<String> invalidationTopics() {
    return Set.of(INVALIDATION_TOPIC);
  }

  @Override
  public void invalidateAll() {
    // read under the exclusion, so a switch of this instance cannot fall between read and replace
    state.exclusively(
        () -> {
          Snapshot current = state.snapshot();
          store
              .load()
              .ifPresent(
                  record ->
                      state.replace(
                          new Snapshot(
                              record.current(),
                              current == null ? configuredOf(record) : current.configured(),
                              record.target())));
          return null;
        });
  }

  private static EmbeddingIdentity configuredOf(EmbeddingIndexRecord record) {
    return record.target() == null ? record.current() : record.target();
  }
}
