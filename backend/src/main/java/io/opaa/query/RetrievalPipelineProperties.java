package io.opaa.query;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which registered stages are switched off for this installation.
 *
 * <p>An Ebene-1 value in the sense of
 * docs/features/hybrid-retrieval.md#konfigurations-ebenenmodell: overridable for development and
 * for the benchmark, but deliberately absent from every administration surface - nobody can judge
 * the effect of removing a retrieval stage without running the benchmark, which is exactly the test
 * the specification applies to every parameter.
 *
 * @param disabledStages stages the pipeline skips. Empty by default: the shipped pipeline runs
 *     every registered stage. {@link RetrievalStageName#SEARCH_SCOPE} is rejected here - see {@link
 *     RetrievalStage#switchable()}.
 */
@ConfigurationProperties(prefix = "opaa.query.pipeline")
public record RetrievalPipelineProperties(
    @DefaultValue({}) Set<RetrievalStageName> disabledStages) {

  public RetrievalPipelineProperties {
    disabledStages = disabledStages == null ? Set.of() : Set.copyOf(disabledStages);
  }

  /** The shipped configuration: every stage runs. */
  public static RetrievalPipelineProperties allStagesEnabled() {
    return new RetrievalPipelineProperties(Set.of());
  }
}
