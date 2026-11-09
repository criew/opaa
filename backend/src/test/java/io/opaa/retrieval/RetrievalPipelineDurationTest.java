package io.opaa.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.RetrievalStageName;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Per-stage durations in the protocol: every stage the pipeline called carries the wall-clock time
 * of its call - including one that reports itself unavailable - and a stage that was switched off
 * (by the pipeline or by itself) or never reached carries none.
 */
class RetrievalPipelineDurationTest {

  private static final QueryProperties PROPERTIES =
      new QueryProperties(8, 25, 1.0, 0.3, false, 3, 2, false, 50, 20, 2);

  /** A monotonic clock the stages advance by hand, so every measured duration is exact. */
  private final AtomicLong nanos = new AtomicLong();

  private RetrievalStage stage(
      RetrievalStageName name, long takesMs, Function<RetrievalState, StageOutcome> outcome) {
    return new RetrievalStage() {
      @Override
      public RetrievalStageName name() {
        return name;
      }

      @Override
      public StageOutcome apply(RetrievalContext context, RetrievalState state) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(takesMs));
        return outcome.apply(state);
      }
    };
  }

  private static StageOutcome executed(RetrievalStageName name, RetrievalState state) {
    return new StageOutcome(state, StageExplanation.executed(name, 0, 0, List.of(), List.of()));
  }

  @Test
  void calledStagesCarryTheirDurationAndStagesNotCalledCarryNone() {
    RetrievalPipeline pipeline =
        new RetrievalPipeline(
            List.of(
                stage(
                    RetrievalStageName.SEARCH_SCOPE,
                    3,
                    state -> executed(RetrievalStageName.SEARCH_SCOPE, state)),
                stage(
                    RetrievalStageName.METADATA_FILTER,
                    500,
                    state -> executed(RetrievalStageName.METADATA_FILTER, state)),
                stage(
                    RetrievalStageName.MMR_SELECTION,
                    4,
                    state ->
                        new StageOutcome(
                            state,
                            StageExplanation.notRun(
                                RetrievalStageName.MMR_SELECTION,
                                StageStatus.DISABLED,
                                0,
                                0,
                                "switched off by its own parameter"))),
                stage(
                    RetrievalStageName.RERANK,
                    1200,
                    state ->
                        new StageOutcome(
                            state,
                            StageExplanation.notRun(
                                RetrievalStageName.RERANK,
                                StageStatus.UNAVAILABLE,
                                0,
                                0,
                                "endpoint failed"))),
                stage(
                    RetrievalStageName.RELEVANCE_CUTOFF,
                    7,
                    state -> executed(RetrievalStageName.RELEVANCE_CUTOFF, state.haltRun())),
                stage(
                    RetrievalStageName.DOCUMENT_COMPLETION,
                    9,
                    state -> executed(RetrievalStageName.DOCUMENT_COMPLETION, state))),
            new RetrievalPipelineProperties(Set.of(RetrievalStageName.METADATA_FILTER)),
            nanos::get);

    RetrievalPipelineResult result =
        pipeline.run(RetrievalPipelineTestSupport.context(Set.of(UUID.randomUUID()), PROPERTIES));

    Map<RetrievalStageName, StageExplanation> byStage =
        result.explanation().stages().stream()
            .collect(Collectors.toMap(StageExplanation::stage, Function.identity()));
    assertThat(byStage.get(RetrievalStageName.SEARCH_SCOPE).durationMs()).isEqualTo(3L);
    assertThat(byStage.get(RetrievalStageName.METADATA_FILTER).status())
        .isEqualTo(StageStatus.DISABLED);
    assertThat(byStage.get(RetrievalStageName.METADATA_FILTER).durationMs()).isNull();
    assertThat(byStage.get(RetrievalStageName.MMR_SELECTION).durationMs()).isNull();
    assertThat(byStage.get(RetrievalStageName.RERANK).status()).isEqualTo(StageStatus.UNAVAILABLE);
    assertThat(byStage.get(RetrievalStageName.RERANK).durationMs()).isEqualTo(1200L);
    assertThat(byStage.get(RetrievalStageName.RELEVANCE_CUTOFF).durationMs()).isEqualTo(7L);
    assertThat(byStage.get(RetrievalStageName.DOCUMENT_COMPLETION).status())
        .isEqualTo(StageStatus.NOT_REACHED);
    assertThat(byStage.get(RetrievalStageName.DOCUMENT_COMPLETION).durationMs()).isNull();
  }
}
