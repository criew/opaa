package io.opaa.indexing;

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
 * {@code UPLOAD} cannot be looked up here at all, because it is not a value of {@link
 * IndexingSourceType} in the first place - the missing mapping the ADR calls out is excluded by the
 * type system, not handled as a runtime case.
 *
 * <p><b>Completeness is checked at construction, not at resolve time.</b> A missing executor for a
 * declared {@link IndexingSourceType} is a wiring bug (a new enum value was added without its
 * matching {@code @Bean}), and this constructor fails application startup with a clear message
 * instead of letting {@link #resolve(IndexingSourceType)} throw the first time some caller happens
 * to hit the gap - which, reached through an HTTP request, would otherwise surface as an opaque 500
 * rather than a startup failure a developer sees immediately.
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
