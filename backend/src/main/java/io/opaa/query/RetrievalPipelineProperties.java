package io.opaa.query;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which registered stages are switched off for this installation.
 *
 * <p>An Ebene-1 value in the sense of
 * docs/features/hybrid-retrieval.md#konfigurations-ebenenmodell: overridable for development and
 * for a deliberate experiment, but deliberately absent from every administration surface - nobody
 * can judge the effect of removing a retrieval stage without a measurement, which is exactly the
 * test the specification applies to every parameter.
 *
 * <p><b>Not a benchmark knob as it stands.</b> The retrieval harness rejects a non-empty set
 * outright ({@code PipelineHarnessSupport#requireMeasurableConfiguration}): no field of a pipeline
 * report records which stages ran, so a run with a stage switched off would carry the same {@code
 * runConfiguration} fingerprint as a full one, and its numbers would be judged against the
 * committed baseline as a code change. Measuring a stage's contribution requires extending the
 * measurement contract first - a new fixed point, a raised contract version, re-drawn baselines.
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
