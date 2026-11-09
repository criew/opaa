package io.opaa.retrieval;

import io.opaa.api.types.RetrievalStageName;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The retrieval pipeline: a fixed, ordered sequence of named {@link RetrievalStage}s, run over one
 * {@link RetrievalContext} (docs/handbuch/referenz/wie-die-suche-arbeitet.md).
 *
 * <p>The order is data, not control flow: it is decided in {@code
 * RetrievalConfiguration#retrievalPipeline} alone. A stage switched off there is switched off
 * everywhere, including the candidate budget it would have widened. Every registered stage appears
 * in the protocol regardless - switched off as {@link StageStatus#DISABLED}, never reached as
 * {@link StageStatus#NOT_REACHED} - so no candidate can disappear between two stages of a diagnosis
 * that looks complete. Thread-safe and stateless: all per-run parameters travel in the context, so
 * one instance serves every caller and every parameter variant.
 */
public class RetrievalPipeline {

  private final List<RetrievalStage> stages;
  private final Set<RetrievalStageName> disabledStages;
  private final LongSupplier nanoTicker;

  /**
   * @param stages the registered stages, in execution order. Rejects duplicates: two stages of the
   *     same name would make the protocol ambiguous about which one a verdict belongs to.
   * @param properties which of them are switched off for this pipeline.
   */
  public RetrievalPipeline(List<RetrievalStage> stages, RetrievalPipelineProperties properties) {
    this(stages, properties, System::nanoTime);
  }

  /**
   * @param nanoTicker monotonic time source in nanoseconds for the per-stage durations; replaced in
   *     tests to make the measured durations deterministic.
   */
  RetrievalPipeline(
      List<RetrievalStage> stages,
      RetrievalPipelineProperties properties,
      LongSupplier nanoTicker) {
    Set<RetrievalStageName> registered = EnumSet.noneOf(RetrievalStageName.class);
    for (RetrievalStage stage : stages) {
      if (!registered.add(stage.name())) {
        throw new IllegalArgumentException("stage registered twice: " + stage.name());
      }
    }
    for (RetrievalStageName disabled : properties.disabledStages()) {
      if (!registered.contains(disabled)) {
        throw new IllegalArgumentException(
            "cannot switch off " + disabled + ": not a registered stage of this pipeline");
      }
    }
    for (RetrievalStage stage : stages) {
      if (!stage.switchable() && properties.disabledStages().contains(stage.name())) {
        throw new IllegalArgumentException(
            "cannot switch off "
                + stage.name()
                + ": it establishes the permission filter, and a run without one is a permission"
                + " bypass, not a pipeline variant");
      }
    }
    this.stages = List.copyOf(stages);
    this.disabledStages = Set.copyOf(properties.disabledStages());
    this.nanoTicker = nanoTicker;
  }

  /** The stages this pipeline runs, in order - the number the protocol must always match. */
  public List<RetrievalStageName> registeredStages() {
    return stages.stream().map(RetrievalStage::name).toList();
  }

  /**
   * Runs every registered stage in order and returns the selection together with the complete
   * explanation protocol. {@code context.searchScope()} is taken as given: this method applies it
   * as the {@code library_id} filter of every search but resolves no permissions of its own. Once a
   * stage halts the run, the remaining stages are recorded as not reached instead of being
   * executed. Every stage that is called gets its wall-clock duration stamped into its explanation;
   * a stage running once per sub-query measures all sub-queries together, as they run in sequence.
   */
  public RetrievalPipelineResult run(RetrievalContext rawContext) {
    // Without the rerank stage nothing would restore the top-k cap, so the fusion must
    // not widen its budget for it. Enforced here: the call sites cannot see the disabled set.
    RetrievalContext context =
        disabledStages.contains(RetrievalStageName.RERANK)
            ? rawContext.withoutReranking()
            : rawContext;
    RetrievalState state = RetrievalState.initial();
    List<StageExplanation> explanations = new ArrayList<>(stages.size());

    for (RetrievalStage stage : stages) {
      if (state.halted() || disabledStages.contains(stage.name())) {
        StageStatus status = state.halted() ? StageStatus.NOT_REACHED : StageStatus.DISABLED;
        int candidateCount = state.candidateCount();
        explanations.add(StageExplanation.notRun(stage.name(), status, candidateCount));
        continue;
      }
      long startedAt = nanoTicker.getAsLong();
      StageOutcome outcome = stage.apply(context, state);
      long durationMs = TimeUnit.NANOSECONDS.toMillis(nanoTicker.getAsLong() - startedAt);
      state = outcome.state();
      explanations.add(outcome.explanation().withDurationMs(durationMs));
    }

    return new RetrievalPipelineResult(
        state.selection(),
        state.searchQueries(),
        new RetrievalExplanation(explanations),
        state.searchNeeded());
  }
}
