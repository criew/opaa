package io.opaa.indexing;

import java.util.List;
import java.util.Map;
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
 */
public class IndexingSourceExecutorRegistry {

  private final Map<IndexingSourceType, SourceIndexingExecutor> executorsByType;

  public IndexingSourceExecutorRegistry(List<SourceIndexingExecutor> executors) {
    this.executorsByType =
        executors.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    SourceIndexingExecutor::sourceType, Function.identity()));
  }

  /**
   * Returns the executor registered for {@code sourceType}.
   *
   * @throws IllegalStateException if no executor is registered for {@code sourceType} - a
   *     verständliche Ablehnung instead of a {@code NullPointerException} further down the call
   *     chain, and a sign that a new {@link IndexingSourceType} value was added without wiring its
   *     matching bean in {@code IndexingConfiguration}.
   */
  public SourceIndexingExecutor resolve(IndexingSourceType sourceType) {
    SourceIndexingExecutor executor = executorsByType.get(sourceType);
    if (executor == null) {
      throw new IllegalStateException(
          "No SourceIndexingExecutor is registered for source type " + sourceType);
    }
    return executor;
  }
}
