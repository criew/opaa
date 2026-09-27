package io.opaa.indexing.source;

import io.opaa.knowledge.SourceType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the {@link SourceIndexingExecutor} responsible for a given {@link SourceType} (ADR-0017,
 * decision 3). Populated from whatever {@link SourceIndexingExecutor} beans Spring finds - each
 * connector declares its own in its package's {@code @Configuration} - so a new source type becomes
 * reachable by adding one more bean, without touching this class or any of the call sites that use
 * it.
 *
 * <p>Completeness is checked at construction: the executors serve exactly the types whose connector
 * declares an indexing run, so a gap is a wiring bug that fails startup instead of the first
 * trigger.
 */
public class IndexingSourceExecutorRegistry {

  private final Map<SourceType, SourceIndexingExecutor> executorsByType;

  public IndexingSourceExecutorRegistry(
      List<SourceIndexingExecutor> executors, SourceConnectorRegistry connectors) {
    this.executorsByType =
        executors.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    SourceIndexingExecutor::sourceType, Function.identity()));
    Set<SourceType> runBased = new HashSet<>();
    connectors.descriptors().stream()
        .filter(SourceConnectorDescriptor::indexingRun)
        .forEach(descriptor -> runBased.add(descriptor.type()));
    if (!runBased.equals(executorsByType.keySet())) {
      throw new IllegalStateException(
          "SourceIndexingExecutor beans serve "
              + executorsByType.keySet()
              + " but the connectors with an indexing run are "
              + runBased);
    }
  }

  /** The executor registered for {@code sourceType}, empty for a type without a run. */
  public Optional<SourceIndexingExecutor> find(SourceType sourceType) {
    return Optional.ofNullable(executorsByType.get(sourceType));
  }
}
