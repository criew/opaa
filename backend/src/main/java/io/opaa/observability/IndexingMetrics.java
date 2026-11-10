package io.opaa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Collection;
import java.util.Locale;

/**
 * Encapsulates all Micrometer metrics related to the document indexing pipeline. A {@code
 * connector} tag is the key of the library's source type in lower case ({@code confluence}, {@code
 * sharepoint}, {@code upload}), the same value {@code opaa.outbound.requests} names as {@code
 * kind}; neither library, folder nor document is ever a tag (ADR-0071, Entscheidung 2).
 */
public class IndexingMetrics {

  /** The {@code connector} of a run whose library is gone. */
  public static final String NO_CONNECTOR = "none";

  /**
   * The {@code connector} of every run of an owner-only library, and the {@code failure_category}
   * its person-bound failures share: no series traces one person's library (ADR-0071, Entscheidung
   * 9).
   */
  public static final String PRIVATE = "private";

  private static final String[] DOCUMENT_RESULTS = {"processed", "skipped", "failed"};

  /** The {@code failure_category} of a run that ended without one. */
  public static final String NO_CATEGORY = "none";

  private final MeterRegistry meterRegistry;
  private final Counter embeddingInputSplitCounter;
  private final Counter embeddingInputUnverifiedCounter;
  private final Counter embeddingInputCutCounter;
  private final Counter chunksCounter;

  public IndexingMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
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
    this.chunksCounter =
        Counter.builder("opaa.indexing.chunks")
            .description("Chunks written to the index by indexed documents")
            .register(meterRegistry);
  }

  /**
   * The {@code connector} tag value of the source type {@code sourceTypeKey}: the key in lower
   * case, {@value #NO_CONNECTOR} without one.
   */
  public static String connector(String sourceTypeKey) {
    return sourceTypeKey == null ? NO_CONNECTOR : sourceTypeKey.toLowerCase(Locale.ROOT);
  }

  /**
   * Registers every {@code result} series of {@code opaa.indexing.documents} at 0 for each of
   * {@code sourceTypeKeys}, so a pod reports them from its start: a first run that never processes
   * a document shows as no increase instead of no series at all.
   */
  public void registerDocumentSeries(Collection<String> sourceTypeKeys) {
    for (String key : sourceTypeKeys) {
      for (String result : DOCUMENT_RESULTS) {
        documentsCounter(key, result);
      }
    }
  }

  public void recordProcessed(String sourceTypeKey) {
    documentsCounter(sourceTypeKey, "processed").increment();
  }

  public void recordSkipped(String sourceTypeKey) {
    documentsCounter(sourceTypeKey, "skipped").increment();
  }

  public void recordFailed(String sourceTypeKey) {
    documentsCounter(sourceTypeKey, "failed").increment();
  }

  private Counter documentsCounter(String sourceTypeKey, String result) {
    return Counter.builder("opaa.indexing.documents")
        .tag("connector", connector(sourceTypeKey))
        .tag("result", result)
        .description("Documents handled by indexing, by connector type and result")
        .register(meterRegistry);
  }

  /** {@code count} chunks one indexed document wrote to the index. */
  public void recordChunks(int count) {
    if (count > 0) {
      chunksCounter.increment(count);
    }
  }

  /**
   * One document whose format yielded no usable text: {@code reason} is {@code parse_failed},
   * {@code no_content} or {@code no_text}, {@code contentType} only decides the {@link MimeClass}.
   */
  public void recordParseFailure(String contentType, String reason) {
    Counter.builder("opaa.indexing.parse.failures")
        .tag("mime_class", MimeClass.of(contentType).tagValue())
        .tag("reason", reason)
        .description("Documents whose format yielded no usable text, by file class and reason")
        .register(meterRegistry)
        .increment();
  }

  /**
   * One ended indexing run: {@code connector} is a {@link #connector} value or {@value #PRIVATE},
   * {@code result} {@code completed}, {@code incomplete} or {@code failed}, {@code mode} {@code
   * full}, {@code delta} or {@code event}, {@code failureCategory} the lower-case run failure
   * category, {@value #NO_CATEGORY}, {@code abandoned} or {@value #PRIVATE}. {@code bytes} are the
   * bytes the run downloaded, counted once at its end.
   */
  public void recordRunEnded(
      String connector,
      String mode,
      String result,
      String failureCategory,
      Duration duration,
      long bytes) {
    Counter.builder("opaa.indexing.runs")
        .tag("connector", connector)
        .tag("mode", mode)
        .tag("result", result)
        .tag("failure_category", failureCategory)
        .description("Ended indexing runs")
        .register(meterRegistry)
        .increment();
    if (duration != null && !duration.isNegative()) {
      Timer.builder("opaa.indexing.run.duration")
          .tag("connector", connector)
          .tag("result", result)
          .description("Duration of an indexing run from its start to its end")
          .register(meterRegistry)
          .record(duration);
    }
    if (bytes > 0) {
      Counter.builder("opaa.indexing.bytes")
          .tag("connector", connector)
          .baseUnit("bytes")
          .description("Bytes indexing runs downloaded from their sources")
          .register(meterRegistry)
          .increment(bytes);
    }
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
   * injection. Counted installation-wide: neither library nor document is a label (ADR-0071); the
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
