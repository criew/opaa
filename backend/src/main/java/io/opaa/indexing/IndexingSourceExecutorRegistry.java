package io.opaa.indexing;

import io.opaa.api.types.DocumentSourceType;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the {@link SourceIndexingExecutor} responsible for a given {@link IndexingSourceType}
 * (ADR-0017, decision 3). Populated from whatever {@link SourceIndexingExecutor} beans Spring finds
 * - see {@code IndexingConfiguration} - so a new source type becomes reachable by adding one more
 * bean, without touching this class or any of the call sites that use it.
 *
 * <p>The key space is deliberately {@link IndexingSourceType}, not {@link DocumentSourceType}:
 * {@code UPLOAD} cannot be looked up here at all, since it is not a value of {@link
 * IndexingSourceType} in the first place.
 *
 * <p>Completeness is checked at construction, not at resolve time: a missing executor for a
 * declared {@link IndexingSourceType} is a wiring bug, and this constructor fails application
 * startup with a clear message instead of letting {@link #resolve(IndexingSourceType)} throw the
 * first time some caller happens to hit the gap.
 */
public class IndexingSourceExecutorRegistry {

  private final Map<IndexingSourceType, SourceIndexingExecutor> executorsByType;

  public IndexingSourceExecutorRegistry(List<SourceIndexingExecutor> executors) {
    this.executorsByType =
        executors.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    SourceIndexingExecutor::sourceType, Function.identity()));
    Set<IndexingSourceType> missing = EnumSet.allOf(IndexingSourceType.class);
    missing.removeAll(executorsByType.keySet());
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "No SourceIndexingExecutor bean is registered for source type(s) " + missing);
    }
  }

  /** Returns the executor registered for {@code sourceType}. Always succeeds after construction. */
  public SourceIndexingExecutor resolve(IndexingSourceType sourceType) {
    return executorsByType.get(sourceType);
  }
}
