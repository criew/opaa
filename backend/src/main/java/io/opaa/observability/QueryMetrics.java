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
  private final Counter degenerateDecompositionCounter;
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
        Counter.builder("opaa.query.decomposition_fallback")
            .tag("reason", "degenerate")
            .description("Query decompositions discarded as unrelated to the question")
            .register(meterRegistry);
    this.failedDecompositionCounter =
        Counter.builder("opaa.query.decomposition_fallback")
            .tag("reason", "failed")
            .description("Query decompositions that could not be obtained at all")
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
   * A decomposition that returned output the pipeline could not use - empty, or with no sub-query
   * related to the question (#1254). Separate from {@link #recordFailedDecomposition()} because a
   * rising count here points at the prompt or the chat model, not at availability.
   */
  public void recordDegenerateDecomposition() {
    degenerateDecompositionCounter.increment();
  }

  /** A decomposition that never produced output: no active chat model, timeout, call error. */
  public void recordFailedDecomposition() {
    failedDecompositionCounter.increment();
  }
}
