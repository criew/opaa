package io.opaa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Encapsulates all Micrometer metrics related to the query pipeline. */
public class QueryMetrics {

  private final Timer queryTimer;
  private final Counter querySuccessCounter;
  private final Counter queryErrorCounter;
  private final Counter tokenCounter;

  /**
   * Every reason the sub-query decomposition fell back to the undecomposed question, on one counter
   * separated by {@code reason} so the shape of the failure is readable without three metric names.
   */
  private static final String DECOMPOSITION_FALLBACK = "opaa.query.decomposition.fallback";

  private final Counter degenerateDecompositionCounter;
  private final Counter prunedDecompositionCounter;
  private final Counter failedDecompositionCounter;

  public QueryMetrics(MeterRegistry meterRegistry) {
    this.queryTimer =
        Timer.builder("opaa.query.duration").description("Query latency").register(meterRegistry);
    this.querySuccessCounter =
        Counter.builder("opaa.query.count")
            .tag("status", "success")
            .description("Successful queries")
            .register(meterRegistry);
    this.queryErrorCounter =
        Counter.builder("opaa.query.count")
            .tag("status", "error")
            .description("Failed queries")
            .register(meterRegistry);
    this.tokenCounter =
        Counter.builder("opaa.query.tokens")
            .description("Total tokens consumed")
            .register(meterRegistry);
    this.degenerateDecompositionCounter =
        Counter.builder(DECOMPOSITION_FALLBACK)
            .tag("reason", "degenerate")
            .description("Query decompositions in which no sub-query related to the question")
            .register(meterRegistry);
    this.prunedDecompositionCounter =
        Counter.builder(DECOMPOSITION_FALLBACK)
            .tag("reason", "pruned")
            .description("Query decompositions in which some sub-queries were unrelated")
            .register(meterRegistry);
    this.failedDecompositionCounter =
        Counter.builder(DECOMPOSITION_FALLBACK)
            .tag("reason", "failed")
            .description("Query decompositions that produced no usable output at all")
            .register(meterRegistry);
  }

  public Timer queryTimer() {
    return queryTimer;
  }

  public void recordSuccess(int tokenCount) {
    querySuccessCounter.increment();
    tokenCounter.increment(tokenCount);
  }

  public void recordError() {
    queryErrorCounter.increment();
  }

  /**
   * The model answered, but no sub-query related to the question (#1254) - it replaced the question
   * instead of restating it. Separate from {@link #recordFailedDecomposition()} because a rising
   * count here points at the prompt or the chat model, not at availability.
   */
  public void recordDegenerateDecomposition() {
    degenerateDecompositionCounter.increment();
  }

  /**
   * Some but not all sub-queries were unrelated. Counted apart from {@link
   * #recordDegenerateDecomposition()} because it is the milder signal: the model understood the
   * question and drifted on part of it.
   */
  public void recordPrunedDecomposition() {
    prunedDecompositionCounter.increment();
  }

  /**
   * No usable output at all: no active chat model, timeout, call error, or an empty/unparsable
   * answer.
   */
  public void recordFailedDecomposition() {
    failedDecompositionCounter.increment();
  }
}
