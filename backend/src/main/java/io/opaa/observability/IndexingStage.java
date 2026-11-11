package io.opaa.observability;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The steps of the indexing pipeline {@code opaa.indexing.stage.duration} times per document as
 * {@code stage}: {@link #FETCH} the download of a file from its source, {@link #PARSE} reading and
 * cutting it in its format (attachments excluded), {@link #OCR} the text recognition of a scan,
 * {@link #CHUNK} preparing the chunks (metadata, context prefix), {@link #EMBED} the embedding
 * calls, {@link #STORE} the write into the index. Measured through the global registry, to which
 * Spring Boot binds the application's, so classes built per run need no registry of their own.
 */
public enum IndexingStage {
  FETCH,
  PARSE,
  OCR,
  CHUNK,
  EMBED,
  STORE;

  private final String tagValue = name().toLowerCase(Locale.ROOT);

  /** Starts timing; the returned nano time goes to {@link #recordSince}. */
  public static long start() {
    return System.nanoTime();
  }

  /** Records the time since {@code startNanos}, a value of {@link #start}. */
  public void recordSince(long startNanos) {
    recordNanos(System.nanoTime() - startNanos);
  }

  /** Records {@code nanos} spent in this stage; a negative value is ignored. */
  public void recordNanos(long nanos) {
    if (nanos < 0) {
      return;
    }
    Timer.builder("opaa.indexing.stage.duration")
        .tag("stage", tagValue)
        .description("Time one document spent in a step of the indexing pipeline")
        .register(Metrics.globalRegistry)
        .record(nanos, TimeUnit.NANOSECONDS);
  }
}
