package io.opaa.query;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The retrieval pipeline: a fixed, ordered sequence of named {@link RetrievalStage}s, run over one
 * {@link RetrievalContext} (docs/features/hybrid-retrieval.md, Arbeitspaket 1). Replaces the
 * seven-step orchestrator {@code QueryService} grew over #912 to #940, without changing what it
 * selects.
 *
 * <p><b>The order is data, not control flow.</b> It is decided at one visible place - {@code
 * QueryConfiguration#retrievalPipeline} - so a question like "does reranking run before or after
 * document completion?" is answered by reading a list rather than by tracing branches.
 *
 * <p><b>Every registered stage appears in the protocol, always.</b> A switched-off stage is
 * recorded as {@link StageStatus#DISABLED}, a stage the run never reached as {@link
 * StageStatus#NOT_REACHED}. A candidate can therefore not disappear between two stages of a
 * diagnosis that looks complete; {@code RetrievalPipelineTest} pins the count.
 *
 * <p>Thread-safe and stateless: the stages hold collaborators, the run holds the state. All per-run
 * parameters travel in the context, so one instance serves every caller and every parameter
 * variant.
 */
public class RetrievalPipeline {

  private final List<RetrievalStage> stages;
  private final Set<RetrievalStageName> disabledStages;

  /**
   * @param stages the registered stages, in execution order. Rejects duplicates: two stages of the
   *     same name would make the protocol ambiguous about which one a verdict belongs to.
   * @param properties which of them are switched off for this pipeline.
   */
  public RetrievalPipeline(List<RetrievalStage> stages, RetrievalPipelineProperties properties) {
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
                + " bypass, not a pipeline variant (ADR-0008 §5)");
      }
    }
    this.stages = List.copyOf(stages);
    this.disabledStages = Set.copyOf(properties.disabledStages());
  }

  /** The stages this pipeline runs, in order - the number the protocol must always match. */
  public List<RetrievalStageName> registeredStages() {
    return stages.stream().map(RetrievalStage::name).toList();
  }

  /**
   * Runs every registered stage in order and returns the selection together with the complete
   * explanation protocol.
   *
   * <p>Once a stage halts the run - today only the empty-scope case in {@link SearchScopeStage} -
   * the remaining stages are recorded as not reached instead of being executed: an empty scope must
   * not pay for a decomposition LLM call whose result nothing would use.
   */
  public RetrievalPipelineResult run(RetrievalContext context) {
    RetrievalState state = RetrievalState.initial();
    List<StageExplanation> explanations = new ArrayList<>(stages.size());

    for (RetrievalStage stage : stages) {
      int candidateCount =
          state.candidateLists().stream().mapToInt(l -> l.documents().size()).sum();
      if (state.halted()) {
        explanations.add(
            StageExplanation.notRun(stage.name(), StageStatus.NOT_REACHED, candidateCount));
        continue;
      }
      if (disabledStages.contains(stage.name())) {
        explanations.add(
            StageExplanation.notRun(stage.name(), StageStatus.DISABLED, candidateCount));
        continue;
      }
      StageOutcome outcome = stage.apply(context, state);
      state = outcome.state();
      explanations.add(outcome.explanation());
    }

    return new RetrievalPipelineResult(
        state.selection(), state.searchQueries(), new RetrievalExplanation(explanations));
  }
}
