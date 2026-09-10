package io.opaa.query.retrieval;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which registered stages are switched off for this installation. An Ebene-1 value in the sense of
 * docs/features/hybrid-retrieval.md#konfigurations-ebenenmodell: overridable for development and
 * for a deliberate experiment, but absent from every administration surface.
 *
 * <p>Not a benchmark knob: the retrieval harness rejects a non-empty set outright ({@code
 * PipelineHarnessSupport#requireMeasurableConfiguration}), because no field of a pipeline report
 * records which stages ran and such a run would carry the fingerprint of a full one.
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
