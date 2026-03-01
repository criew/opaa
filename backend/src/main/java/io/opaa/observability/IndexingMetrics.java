package io.opaa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Encapsulates all Micrometer metrics related to the document indexing pipeline. */
public class IndexingMetrics {

  private final Counter processedCounter;
  private final Counter skippedCounter;
  private final Counter failedCounter;

  public IndexingMetrics(MeterRegistry meterRegistry) {
    this.processedCounter =
        Counter.builder("opaa.indexing.documents")
            .tag("result", "processed")
            .description("Documents processed")
            .register(meterRegistry);
    this.skippedCounter =
        Counter.builder("opaa.indexing.documents")
            .tag("result", "skipped")
            .description("Documents skipped (unchanged)")
            .register(meterRegistry);
    this.failedCounter =
        Counter.builder("opaa.indexing.documents")
            .tag("result", "failed")
            .description("Documents failed")
            .register(meterRegistry);
  }

  public void recordProcessed() {
    processedCounter.increment();
  }

  public void recordSkipped() {
    skippedCounter.increment();
  }

  public void recordFailed() {
    failedCounter.increment();
  }
}
