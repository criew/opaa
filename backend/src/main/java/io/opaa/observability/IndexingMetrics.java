package io.opaa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Encapsulates all Micrometer metrics related to the document indexing pipeline. */
public class IndexingMetrics {

  private final MeterRegistry meterRegistry;
  private final Counter processedCounter;
  private final Counter skippedCounter;
  private final Counter failedCounter;
  private final Counter embeddingInputSplitCounter;
  private final Counter embeddingInputUnverifiedCounter;
  private final Counter embeddingInputCutCounter;

  public IndexingMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
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
    this.embeddingInputSplitCounter =
        Counter.builder("opaa.indexing.embedding.inputs")
            .tag("result", "split")
            .description("Chunks split because the embedding endpoint proved them too long")
            .register(meterRegistry);
    this.embeddingInputUnverifiedCounter =
        Counter.builder("opaa.indexing.embedding.inputs")
            .tag("result", "unverified")
            .description(
                "Chunks that could reach the embedding input limit, embedded without a token count")
            .register(meterRegistry);
    this.embeddingInputCutCounter =
        Counter.builder("opaa.indexing.embedding.inputs")
            .tag("result", "cut")
            .description(
                "Chunks re-embedded in place with a vector the endpoint cut short, pending re-index")
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

  public void recordEmbeddingInputSplit() {
    embeddingInputSplitCounter.increment();
  }

  public void recordEmbeddingInputsUnverified(int count) {
    embeddingInputUnverifiedCounter.increment(count);
  }

  public void recordEmbeddingInputsCut(int count) {
    embeddingInputCutCounter.increment(count);
  }

  /** The {@code library} label every private library shares in {@link #recordSuspiciousPattern}. */
  public static final String PRIVATE_LIBRARY_LABEL = "private";

  /**
   * One indexed document whose text showed {@code patternClass}, a pattern typical of prompt
   * injection. {@code libraryLabel} is a shared library's id or {@link #PRIVATE_LIBRARY_LABEL},
   * never a name or content.
   */
  public void recordSuspiciousPattern(String libraryLabel, String patternClass) {
    Counter.builder("opaa.indexing.suspicious.patterns")
        .tag("library", libraryLabel)
        .tag("class", patternClass)
        .description("Indexed documents with a pattern typical of prompt injection")
        .register(meterRegistry)
        .increment();
  }
}
