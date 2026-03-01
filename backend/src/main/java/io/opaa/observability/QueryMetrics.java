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
}
