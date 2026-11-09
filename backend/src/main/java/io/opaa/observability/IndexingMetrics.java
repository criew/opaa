package io.opaa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

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

  /**
   * One indexed document whose text showed {@code patternClass}, a pattern typical of prompt
   * injection. Counted installation-wide: neither library nor document is a label (ADR-0070); the
   * run protocol of the library names the document.
   */
  public void recordSuspiciousPattern(String patternClass) {
    Counter.builder("opaa.indexing.suspicious.patterns")
        .tag("class", patternClass)
        .description("Indexed documents with a pattern typical of prompt injection")
        .register(meterRegistry)
        .increment();
  }

  /**
   * One text recognition (OCR) attempt of a scanned document: its {@code result} (recognized,
   * reused, failed, blocked, pending), the {@code connectionType} of the service, the pages sent
   * and how long it took. Neither library, account nor document is a label.
   */
  public void recordTextRecognition(
      String result, String connectionType, int pagesSent, Duration duration) {
    Counter.builder("opaa.ocr.documents")
        .tag("result", result)
        .tag("connection_type", connectionType)
        .description("Scanned documents handled by text recognition, by outcome")
        .register(meterRegistry)
        .increment();
    if (pagesSent > 0) {
      Counter.builder("opaa.ocr.pages")
          .tag("connection_type", connectionType)
          .description("Pages sent to the text recognition service")
          .register(meterRegistry)
          .increment(pagesSent);
    }
    if (duration != null) {
      Timer.builder("opaa.ocr.duration")
          .tag("result", result)
          .tag("connection_type", connectionType)
          .description("Duration of text recognition per document")
          .register(meterRegistry)
          .record(duration);
    }
  }
}
