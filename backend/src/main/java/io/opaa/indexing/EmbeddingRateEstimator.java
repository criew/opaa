package io.opaa.indexing;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How long embedding one chunk takes on this installation - the rate behind "4.812 Chunks, rund 40
 * Minuten" (metadata-schema.md, "Der Reindex-Preis, ehrlich ausgewiesen"). Contract: the measured
 * mean of the embedding calls this process has actually made once {@link #MIN_MEASURED_CHUNKS} of
 * them exist, the configured {@code opaa.indexing.embedding-rate-estimate} before that - a fresh
 * installation must still be able to name a number. Process-lifetime state (ADR-0021).
 */
@Component
public class EmbeddingRateEstimator {

  /** Below this many measured chunks a mean says more about warm-up than about throughput. */
  static final long MIN_MEASURED_CHUNKS = 20;

  /** Where the rate a given estimate used came from. */
  public enum RateSource {
    MEASURED,
    CONFIGURED
  }

  private final double configuredChunksPerSecond;
  private final AtomicLong measuredChunks = new AtomicLong();
  private final AtomicLong measuredNanos = new AtomicLong();

  /**
   * Embedding calls currently in flight, and how many have ever started. Only a call that was alone
   * at both ends contributes a measurement: the wall times of concurrent sub-batches ({@code
   * opaa.indexing.embedding-concurrency}) overlap, and summing them would report a rate up to that
   * factor too pessimistic - while still calling it {@code MEASURED}.
   */
  private final AtomicInteger inFlight = new AtomicInteger();

  private final AtomicLong starts = new AtomicLong();

  public EmbeddingRateEstimator(
      @Value("${opaa.indexing.embedding-rate-estimate:4.0}") double configuredChunksPerSecond) {
    this.configuredChunksPerSecond =
        configuredChunksPerSecond > 0 ? configuredChunksPerSecond : 4.0;
  }

  /**
   * Marks an embedding call as started; the returned token is handed back to {@link #record}. Every
   * caller must pair the two, in a {@code try}/{@code finally} if the call may throw.
   */
  public long started() {
    inFlight.incrementAndGet();
    return starts.incrementAndGet();
  }

  /**
   * Adds one completed embedding call to the mean - unless another one overlapped it, in which case
   * its wall time says more about the concurrency than about the throughput and is dropped.
   *
   * @param token what {@link #started()} returned for this call
   */
  public void record(int chunks, long nanos, long token) {
    // Alone at the end and no further call started in between: exactly the calls whose wall time
    // is the duration of that embedding round trip and nothing else.
    boolean ranAlone = inFlight.getAndDecrement() == 1 && starts.get() == token;
    if (!ranAlone || chunks <= 0 || nanos <= 0) {
      return;
    }
    measuredChunks.addAndGet(chunks);
    measuredNanos.addAndGet(nanos);
  }

  public RateSource rateSource() {
    return measuredChunks.get() >= MIN_MEASURED_CHUNKS
        ? RateSource.MEASURED
        : RateSource.CONFIGURED;
  }

  /** Seconds per chunk at the current rate; never zero, so an estimate is never falsely instant. */
  public double secondsPerChunk() {
    long chunks = measuredChunks.get();
    if (chunks >= MIN_MEASURED_CHUNKS) {
      return measuredNanos.get() / 1_000_000_000.0 / chunks;
    }
    return 1.0 / configuredChunksPerSecond;
  }

  /** The expected runtime of {@code chunks} embedding calls, rounded up to a full second. */
  public long estimatedSeconds(long chunks) {
    if (chunks <= 0) {
      return 0;
    }
    return (long) Math.ceil(chunks * secondsPerChunk());
  }
}
